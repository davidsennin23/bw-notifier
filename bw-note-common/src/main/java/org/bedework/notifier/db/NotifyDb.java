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
package org.bedework.notifier.db;

import org.bedework.notifier.conf.NotifyConfig;
import org.bedework.notifier.exception.NoteException;
import org.bedework.util.hibernate.HibException;
import org.bedework.util.hibernate.HibSession;
import org.bedework.util.hibernate.HibSessionFactory;
import org.bedework.util.hibernate.HibSessionImpl;

import org.apache.log4j.Logger;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** This class manages the notification database.
 * Not clear what would go in here. Presumably a user could ask
 * for certain kinds of notification in certain ways from specified
 * sources. I guess we store that info here.
 *
 * <p>We can call those subscriptions. Somebody has subscribed (or
 * been subscribed) for notifications.</p>
 *
 * @author Mike Douglass
 */
public class NotifyDb implements Serializable {
  private transient Logger log;

  private final boolean debug;

  private NotifyConfig config;

  /** */
  protected boolean open;

  /** When we were created for debugging */
  protected Timestamp objTimestamp;

  /** Current hibernate session - exists only across one user interaction
   */
  protected HibSession sess;

  /**
   * @param config configuration
   *
   */
  public NotifyDb(final NotifyConfig config) {
    debug = getLogger().isDebugEnabled();

    this.config = config;
  }

  /**
   * @return true if we had to open it. False if already open
   * @throws NoteException
   */
  public boolean open() throws NoteException {
    if (isOpen()) {
      return false;
    }

    openSession();
    open = true;
    return true;
  }

  /**
   * @return true for open
   */
  public boolean isOpen() {
    return open;
  }

  /**
   * @throws NoteException
   */
  public void close() throws NoteException {
    try {
      endTransaction();
    } catch (final NoteException ne) {
      try {
        rollbackTransaction();
      } catch (final NoteException ignored) {}
      throw ne;
    } finally {
      try {
        closeSession();
      } catch (final NoteException ignored) {}
      open = false;
    }
  }

  /* ====================================================================
   *                   Subscription Object methods
   * ==================================================================== */

  private static final String getAllQuery =
          "from " + Subscription.class.getName();

  /**
   * @return list of subscriptions
   * @throws NoteException
   */
  @SuppressWarnings("unchecked")
  public List<Subscription> getAll() throws NoteException {
    try {
      sess.createQuery(getAllQuery);

      return wrap(sess.getList());
    } catch (final HibException he) {
      throw new NoteException(he);
    }
  }

  private static final String getSubQuery =
          "from " + Subscription.class.getName() +
                  " sub where sub.subscriptionId=:subid";

  /** The notify engine generates a unique subscription id
   * for each subscription. This is used as a key for each subscription.
   *
   * @param id - unique id
   * @return a matching subscription or null
   * @throws NoteException
   */
  public Subscription get(final String id) throws NoteException {
    try {
      sess.createQuery(getSubQuery);
      sess.setString("subid", id);

      return wrap((Subscription)sess.getUnique());
    } catch (final HibException he) {
      throw new NoteException(he);
    }
  }

  private static final String findSubQuery =
          "from " + Subscription.class.getName() +
                  " sub where sub.connectorName=:connName" +
                  " and sub.principalHref=:pref";

  /** Find any subscription that matches this one. There can only be one with
   * the same endpoints
   *
   * @param conName name of connector
   * @param principalHref of subscription
   * @return matching subscriptions
   * @throws NoteException
   */
  public Subscription find(final String conName,
                           final String principalHref) throws NoteException {
    try {
      sess.createQuery(findSubQuery);
      sess.setString("connName", conName);
      sess.setString("pref", principalHref);

      return wrap((Subscription)sess.getUnique());
    } catch (final HibException he) {
      throw new NoteException(he);
    }
  }

  /** Find any subscription that matches this one. There can only be one with
   * the same endpoints
   *
   * @param sub subscription
   * @return matching subscriptions
   * @throws NoteException
   */
  public Subscription find(final Subscription sub) throws NoteException {
    return find(sub.getConnectorName(),
                sub.getPrincipalHref());
  }

  /** Add the subscription.
   *
   * @param sub subscription
   * @throws NoteException
   */
  public void add(final Subscription sub) throws NoteException {
    try {
      sess.save(unwrap(sub));
    } catch (final HibException he) {
      throw new NoteException(he);
    }
  }

  /** Update the persisted state of the subscription.
   *
   * @param sub subscription
   * @throws NoteException
   */
  public void update(final Subscription sub) throws NoteException {
    try {
      sess.update(unwrap(sub));
    } catch (final HibException he) {
      throw new NoteException(he);
    }
  }

  /** Delete the subscription.
   *
   * @param sub subscription
   * @throws NoteException
   */
  public void delete(final Subscription sub) throws NoteException {
    try {
      sess.delete(unwrap(sub));
    } catch (final HibException he) {
      throw new NoteException(he);
    }
  }

  /* ====================================================================
   *                   Session methods
   * ==================================================================== */

  protected void checkOpen() throws NoteException {
    if (!isOpen()) {
      throw new NoteException("Session call when closed");
    }
  }

  protected synchronized void openSession() throws NoteException {
    if (isOpen()) {
      throw new NoteException("Already open");
    }

    open = true;

    if (sess != null) {
      warn("Session is not null. Will close");
      try {
        close();
      } catch (final Throwable ignored) {
      }
    }

    if (sess == null) {
      if (debug) {
        trace("New hibernate session for " + objTimestamp);
      }
      sess = new HibSessionImpl();
      try {
        sess.init(HibSessionFactory.getSessionFactory(
                config.getHibernateProperties()), getLogger());
      } catch (HibException he) {
        throw new NoteException(he);
      }
      trace("Open session for " + objTimestamp);
    }

    beginTransaction();
  }

  protected synchronized void closeSession() throws NoteException {
    if (!isOpen()) {
      if (debug) {
        trace("Close for " + objTimestamp + " closed session");
      }
      return;
    }

    if (debug) {
      trace("Close for " + objTimestamp);
    }

    try {
      if (sess != null) {
        if (sess.rolledback()) {
          sess = null;
          return;
        }

        if (sess.transactionStarted()) {
          sess.rollback();
        }
//        sess.disconnect();
        sess.close();
        sess = null;
      }
    } catch (final Throwable t) {
      try {
        sess.close();
      } catch (final Throwable ignored) {}
      sess = null; // Discard on error
    } finally {
      open = false;
    }
  }

  protected void beginTransaction() throws NoteException {
    checkOpen();

    if (debug) {
      trace("Begin transaction for " + objTimestamp);
    }
    try {
      sess.beginTransaction();
    } catch (HibException he) {
      throw new NoteException(he);
    }
  }

  protected void endTransaction() throws NoteException {
    checkOpen();

    if (debug) {
      trace("End transaction for " + objTimestamp);
    }

    try {
      if (!sess.rolledback()) {
        sess.commit();
      }
    } catch (HibException he) {
      throw new NoteException(he);
    }
  }

  protected void rollbackTransaction() throws NoteException {
    try {
      checkOpen();
      sess.rollback();
    } catch (HibException he) {
      throw new NoteException(he);
    }
  }

  /**
   * @return Logger
   */
  protected Logger getLogger() {
    if (log == null) {
      log = Logger.getLogger(this.getClass());
    }

    return log;
  }

  /**
   * @param t the exception
   */
  protected void error(final Throwable t) {
    getLogger().error(this, t);
  }

  /**
   * @param msg the message
   */
  protected void warn(final String msg) {
    getLogger().warn(msg);
  }

  /**
   * @param msg the message
   */
  protected void trace(final String msg) {
    getLogger().debug(msg);
  }

  /* ====================================================================
   *                   private methods
   * ==================================================================== */

  private Subscription unwrap(final Subscription val) {
    if (val == null) {
      return null;
    }

    if (val instanceof SubscriptionWrapper) {
      return ((SubscriptionWrapper)val).getSubscription();
    }

    return val;
  }

  private Subscription wrap(final Subscription val) {
    if (val == null) {
      return null;
    }

    return new SubscriptionWrapper(val);
  }

  private List<Subscription> wrap(final List<Subscription> val) {
    if (val == null) {
      return null;
    }

    final List<Subscription> wrapped = new ArrayList<>();

    for (final Subscription sub: val) {
      wrapped.add(wrap(sub));
    }

    return wrapped;
  }
}
