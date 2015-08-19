/* ********************************************************************
    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.
*/
package org.bedework.notifier;

import org.bedework.notifier.cnctrs.Connector;
import org.bedework.notifier.cnctrs.ConnectorInstance;
import org.bedework.notifier.conf.NotifyConfig;
import org.bedework.notifier.db.NotifyDb;
import org.bedework.notifier.db.Subscription;
import org.bedework.notifier.exception.NoteException;
import org.bedework.notifier.notifications.Note;
import org.bedework.notifier.outbound.common.Adaptor;
import org.bedework.util.calendar.XcalUtil.TzGetter;
import org.bedework.util.config.ConfigException;
import org.bedework.util.config.ConfigurationStore;
import org.bedework.util.http.BasicHttpClient;
import org.bedework.util.jmx.ConfigHolder;
import org.bedework.util.misc.Util;
import org.bedework.util.security.PwEncryptionIntf;
import org.bedework.util.timezones.Timezones;
import org.bedework.util.timezones.TimezonesImpl;

import net.fortuna.ical4j.model.TimeZone;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/** Notification processor.
 * <p>The notification processor manages the notification service.
 *
 * <p>There are two ends to a subscription handled by connectors.
 *
 * <p>blah blah<</p>
 *
 * @author Mike Douglass
 */
public class NotifyEngine extends TzGetter {
  protected transient Logger log;

  private final boolean debug;

  //private static String appname = "Synch";
  static ConfigHolder<NotifyConfig> cfgHolder;

  private transient PwEncryptionIntf pwEncrypt;

  /* Map of currently active notification subscriptions. These are subscriptions
   * for which we get change messages from the remote system(s).
   */
  private final Map<String, Subscription> activeSubs = new HashMap<>();

  private boolean starting;

  private boolean running;

  private boolean stopping;

  //private Configurator config;

  private final static Object getNotifierLock = new Object();

  private static NotifyEngine notifier;

  private Timezones timezones;

  static TzGetter tzgetter;

  private NotelingPool notelingPool;

  private AdaptorPool adaptorPool;

  private NotifyTimer notifyTimer;

  /* Where we keep subscriptions that come in while we are starting */
  private List<Subscription> subsList;

  private NotifyDb db;

  /** Queue and process inbound actions.
   *
   */
  private static ActionQueue actionInHandler;

  /** Queue and process outbound actions.
   *
   */
  private static ActionQueue actionOutHandler;

  public static class NotificationMsg {
    private final String system;
    private final String href;

    public NotificationMsg(final String system,
                           final String href) {
      this.system = system;
      this.href = href;
    }

    public String getSystem() {
      return system;
    }

    public String getHref() {
      return href;
    }
  }

  private static final BlockingDeque<NotificationMsg> notificationMsgs =
          new LinkedBlockingDeque<>();

  public static void addNotificationMsg(final NotificationMsg val) throws NoteException {
    try {
      notificationMsgs.put(val);
    } catch (final Throwable t) {
      throw new NoteException(t);
    }
  }

  /** Constructor
   *
   */
  private NotifyEngine() throws NoteException {
    debug = getLogger().isDebugEnabled();

    System.setProperty("com.sun.xml.ws.transport.http.client.HttpTransportPipe.dump",
                       String.valueOf(debug));
  }

  /**
   * @return the notification engine
   * @throws NoteException
   */
  public static NotifyEngine getNotifier() throws NoteException {
    if (notifier != null) {
      return notifier;
    }

    synchronized (getNotifierLock) {
      if (notifier != null) {
        return notifier;
      }
      notifier = new NotifyEngine();
      return notifier;
    }
  }

  /**
   * @param val the config holder
   */
  public static void setConfigHolder(final ConfigHolder<NotifyConfig> val) {
    cfgHolder = val;
  }

  /**
   * @return current state of the configuration
   */
  public static NotifyConfig getConfig() {
    if (cfgHolder == null) {
      return null;
    }

    return cfgHolder.getConfig();
  }

  public static boolean authenticate(final String system,
                              final String token) throws NoteException {
    NotifyRegistry.Info info = NotifyRegistry.getInfo(system);

    return info != null &&
            info.getAuthenticator().authenticate(token);
  }

  /**
   * @return configuration store
   */
  public static ConfigurationStore getConfigStore() throws NoteException {
    if (cfgHolder == null) {
      return null;
    }

    try {
      return cfgHolder.getStore();
    } catch (final ConfigException ce) {
      throw new NoteException(ce);
    }
  }

  /**
   * @throws NoteException
   */
  public void updateConfig() throws NoteException {
    if (cfgHolder != null) {
      cfgHolder.putConfig();
    }
  }

  /** Get a timezone object given the id. This will return transient objects
   * registered in the timezone directory
   *
   * @param id tzid
   * @return TimeZone with id or null
   * @throws Throwable
   */
   @Override
  public TimeZone getTz(final String id) throws Throwable {
     return getNotifier().timezones.getTimeZone(id);
   }

  /**
   * @param sub to add to the start list
   */
  public void add(final Subscription sub) {
    if (subsList == null) {
      subsList = new ArrayList<>();
    }

    if (!subsList.contains(sub)) {
      subsList.add(sub);
    }
  }

  /** Start notify process.
   *
   * @throws NoteException
   */
  public void start() throws NoteException {
    try {
      if (starting || running) {
        warn("Start called when already starting or running");
        return;
      }

      synchronized (this) {
        subsList = null;

        starting = true;
      }

      db = new NotifyDb(getConfig());

      timezones = new TimezonesImpl();
      timezones.init(getConfig().getTimezonesURI());

      tzgetter = this;

      //DavClient.setDefaultMaxPerHost(20);
      BasicHttpClient.setDefaultMaxPerRoute(20);

      notelingPool = new NotelingPool();
      notelingPool.start(this,
                          getConfig().getNotelingPoolSize(),
                          getConfig().getNotelingPoolTimeout());

      actionInHandler = new ActionQueue(this, "actionIn", notelingPool);
      actionOutHandler = new ActionQueue(this, "actionOut", notelingPool);

      info("**************************************************");
      info("Starting notifier");
      info("      callback URI: " + getConfig().getCallbackURI());
      info("**************************************************");

      if (getConfig().getKeystore() != null) {
        System.setProperty("javax.net.ssl.trustStore", getConfig().getKeystore());
        System.setProperty("javax.net.ssl.trustStorePassword", "bedework");
      }

      final NotifyRegistry registry = new NotifyRegistry();
      registry.registerConnectors(getConfig());
      registry.startConnectors(this);

      adaptorPool = new AdaptorPool(this, 1000 * 60);
      adaptorPool.registerAdaptors();

      notifyTimer = new NotifyTimer(this);

      /* Get the list of subscriptions from our database and process them.
       * While starting, new subscribe requests get added to the list.
       */

      actionInHandler.start();
      actionOutHandler.start();

      try {
        db.open();
        List<Subscription> startList = db.getAll();
        db.close();

        startup:
        while (starting) {
          if (startList == null) {
            if (debug) {
              trace("startList is null");
            }
          } else {
            if (debug) {
              trace("startList has " + startList.size() + " subscriptions");
            }

            for (final Subscription sub: startList) {
              setConnectors(sub);

              reschedule(sub);
            }
          }

          synchronized (this) {
            if (subsList == null) {
              // Nothing came in as we started
              starting = false;
              if (stopping) {
                break startup;
              }
              running = true;
              break;
            }

            startList = subsList;
            subsList = null;
          }
        }
      } finally {
        if ((db != null) && db.isOpen()) {
          db.close();
        }
      }

      info("**************************************************");
      info("Notifier started");
      info("**************************************************");
    } catch (final NoteException se) {
      error(se);
      starting = false;
      running = false;
      throw se;
    } catch (final Throwable t) {
      error(t);
      starting = false;
      running = false;
      throw new NoteException(t);
    }
  }

  /** Reschedule a subscription for updates.
   *
   * @param sub the subscription
   * @throws NoteException
   */
  public void reschedule(final Subscription sub) throws NoteException {
    if (debug) {
      trace("reschedule subscription " + sub);
    }

    /*
    if (sub.polling()) {
      final Action action = new Action(ActionType.fetchItems, sub);

      notifyTimer.schedule(action, sub.nextRefresh());
      return;
    }
    */

    // XXX start up the add to active subs

    activeSubs.put(sub.getSubscriptionId(), sub);
  }

  /** Reschedule an action for retry.
   *
   * @param act the action
   * @throws NoteException
   */
  public void reschedule(final Action act) throws NoteException {
    if (debug) {
      trace("reschedule action after error " + act.getSub());
    }

    notifyTimer.schedule(act, 1 * 60 * 1000);  // 1 minute
  }

  public void queueNotification(final Note note) throws NoteException {

  }

  /**
   * @return true if we're running
   */
  public boolean getRunning() {
    return running;
  }

  /**
   * @return stats for notify service bean
   */
  public List<Stat> getStats() {
    final List<Stat> stats = new ArrayList<>();

    stats.addAll(notelingPool.getStats());
    stats.addAll(notifyTimer.getStats());

    actionInHandler.getStats(stats);
    actionOutHandler.getStats(stats);

    return stats;
  }

  /** Stop notify process.
   *
   */
  public void stop() {
    if (stopping) {
      return;
    }

    stopping = true;

    /* Call stop on each connector
     */
    for (final Connector conn: NotifyRegistry.getConnectors()) {
      info("Stopping connector " + conn.getId());
      try {
        conn.stop();
      } catch (final Throwable t) {
        if (debug) {
          error(t);
        } else {
          error(t.getMessage());
        }
      }
    }

    info("Connectors stopped");

    actionInHandler.shutdown();
    actionOutHandler.shutdown();

    if (notelingPool != null) {
      notelingPool.stop();
    }

    notifier = null;

    info("**************************************************");
    info("Notifier shutdown complete");
    info("**************************************************");
  }

  /**
   * @param action to take
   * @throws NoteException
   */
  public void handleAction(final Action action) throws NoteException {
    switch (action.getType()) {
      case fetchItems:
        actionInHandler.queueAction(action);
        break;
      default:
        actionOutHandler.queueAction(action);
        break;
    }
  }

  /**
   * @param val to decrypt
   * @return decrypted string
   * @throws NoteException
   */
  public String decrypt(final String val) throws NoteException {
    try {
      return getEncrypter().decrypt(val);
    } catch (final NoteException se) {
      throw se;
    } catch (final Throwable t) {
      throw new NoteException(t);
    }
  }

  /**
   * @return en/decryptor
   * @throws NoteException
   */
  public PwEncryptionIntf getEncrypter() throws NoteException {
    if (pwEncrypt != null) {
      return pwEncrypt;
    }

    try {
      final String pwEncryptClass = "org.bedework.util.security.PwEncryptionDefault";
      //String pwEncryptClass = getSysparsHandler().get().getPwEncryptClass();

      pwEncrypt = (PwEncryptionIntf)Util.getObject(pwEncryptClass,
                                                   PwEncryptionIntf.class);

      pwEncrypt.init(getConfig().getPrivKeys(),
                     getConfig().getPubKeys());

      return pwEncrypt;
    } catch (final NoteException se) {
      throw se;
    } catch (final Throwable t) {
      t.printStackTrace();
      throw new NoteException(t);
    }
  }

  /** Gets an instance and implants it in the subscription object.
   * @param sub a subscription
   * @return ConnectorInstance or throws Exception
   * @throws NoteException
   */
  public ConnectorInstance getConnectorInstance(final Subscription sub) throws NoteException {
    ConnectorInstance cinst;
    final Connector conn;

    cinst = sub.getSourceConnInst();
    conn = sub.getSourceConn();

    if (cinst != null) {
      return cinst;
    }

    if (conn == null) {
      throw new NoteException("No connector for " + sub);
    }

    cinst = conn.getConnectorInstance(sub);
    if (cinst == null) {
      throw new NoteException("No connector instance for " + sub);
    }

    sub.setSourceConnInst(cinst);

    return cinst;
  }

  /** When we start up a new subscription we implant a Connector in the object.
   *
   * @param sub a subscription
   * @throws NoteException
   */
  public void setConnectors(final Subscription sub) throws NoteException {
    final String connectorName = sub.getConnectorName();

    final Connector conn = NotifyRegistry.getConnector(connectorName);
    if (conn == null) {
      throw new NoteException("No connector for " + sub + "(");
    }

    sub.setSourceConn(conn);
  }

  /* * Processes a batch of notifications. This must be done in a timely manner
   * as a request is usually hanging on this.
   *
   * @param notes
   * @throws NoteException
   * /
  public void handleNotifications(
            final NotificationBatch<Notification> notes) throws NoteException {
    for (Notification note: notes.getNotifications()) {
      db.open();
      Noteling nl = null;

      try {
        if (note.getNotification() != null) {
          nl = notelingPool.get();

          handleAction(nl, note);
        }
      } finally {
        db.close();
        if (nl != null) {
          notelingPool.add(nl);
        }
      }
    }

    return;
  }*/

  /**
   * @param note a notifier that needs adaptors
   * @return list of adaptors
   * @throws NoteException
   */
  public List<Adaptor> getAdaptors(final Note note) throws NoteException {
    final Adaptor a = adaptorPool.getAdaptor(note.getDeliveryMethod().toString());

    if (a == null) {
      return null;
    }

    final List<Adaptor> as = new ArrayList<>();

    as.add(a);

    return as;
  }

  /**
   * @param adaptors list of adaptors
   * @throws NoteException
   */
  public void releaseAdaptors(final List<Adaptor> adaptors) throws NoteException {
    for (final Adaptor adaptor: adaptors) {
      adaptorPool.add(adaptor);
    }
  }

  /* ====================================================================
   *                        db methods
   * ==================================================================== */

  public void startTransaction() throws NoteException {
    db.open();
  }

  public void endTransaction() throws NoteException {
    if ((db != null) && db.isOpen()) {
      db.close();
    }
  }

  /**
   * @param id key
   * @return subscription
   * @throws NoteException
   */
  public Subscription getSubscription(final String id) throws NoteException {
    return db.get(id);
  }

  /**
   * @param id key
   * @return subscription
   * @throws NoteException
   */
  public Subscription getSubscriptionByOwner(final String id) throws NoteException {
    return db.get(id);
  }

  /**
   * @param sub a subscription
   * @throws NoteException
   */
  public void addSubscription(final Subscription sub) throws NoteException {
    db.add(sub);
  }

  /**
   * @param sub a subscription
   * @throws NoteException
   */
  public void updateSubscription(final Subscription sub) throws NoteException {
    db.update(sub);
  }

  /**
   * @param sub a subscription
   * @throws NoteException
   */
  public void deleteSubscription(final Subscription sub) throws NoteException {
    db.delete(sub);
  }

  /** Find any subscription that matches this one. There can only be one with
   * the same endpoints
   *
   * @param conName name of connector
   * @param owner of subscription
   * @return matching subscriptions
   * @throws org.bedework.notifier.exception.NoteException
   */
  public Subscription find(final String conName,
                           final String owner) throws NoteException {
    return db.find(conName, owner);
  }

  /** Find any subscription that matches this one. There can only be one with
   * the same endpoints
   *
   * @param sub a subscription
   * @return matching subscriptions
   * @throws NoteException
   */
  public Subscription find(final Subscription sub) throws NoteException {
    return db.find(sub);
  }

  /* ====================================================================
   *                        private methods
   * ==================================================================== */

  private Logger getLogger() {
    if (log == null) {
      log = Logger.getLogger(this.getClass());
    }

    return log;
  }

  private void trace(final String msg) {
    getLogger().debug(msg);
  }

  private void warn(final String msg) {
    getLogger().warn(msg);
  }

  private void error(final String msg) {
    getLogger().error(msg);
  }

  private void error(final Throwable t) {
    getLogger().error(this, t);
  }

  private void info(final String msg) {
    getLogger().info(msg);
  }
}
