/*
COPYRIGHT STATUS:
  Dec 1st 2001, Fermi National Accelerator Laboratory (FNAL) documents and
  software are sponsored by the U.S. Department of Energy under Contract No.
  DE-AC02-76CH03000. Therefore, the U.S. Government retains a  world-wide
  non-exclusive, royalty-free license to publish or reproduce these documents
  and software for U.S. Government purposes.  All documents and software
  available from this server are protected under the U.S. and Foreign
  Copyright Laws, and FNAL reserves all rights.


 Distribution of the software available from this server is free of
 charge subject to the user following the terms of the Fermitools
 Software Legal Information.

 Redistribution and/or modification of the software shall be accompanied
 by the Fermitools Software Legal Information  (including the copyright
 notice).

 The user is asked to feed back problems, benefits, and/or suggestions
 about the software to the Fermilab Software Providers.


 Neither the name of Fermilab, the  URA, nor the names of the contributors
 may be used to endorse or promote products derived from this software
 without specific prior written permission.



  DISCLAIMER OF LIABILITY (BSD):

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
  "AS IS" AND ANY EXPRESS OR IMPLIED  WARRANTIES, INCLUDING, BUT NOT
  LIMITED TO, THE IMPLIED  WARRANTIES OF MERCHANTABILITY AND FITNESS
  FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL FERMILAB,
  OR THE URA, OR THE U.S. DEPARTMENT of ENERGY, OR CONTRIBUTORS BE LIABLE
  FOR  ANY  DIRECT, INDIRECT,  INCIDENTAL, SPECIAL, EXEMPLARY, OR
  CONSEQUENTIAL DAMAGES  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
  OF SUBSTITUTE  GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
  BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY  OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT  OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE  POSSIBILITY OF SUCH DAMAGE.


  Liabilities of the Government:

  This software is provided by URA, independent from its Prime Contract
  with the U.S. Department of Energy. URA is acting independently from
  the Government and in its own private capacity and is not acting on
  behalf of the U.S. Government, nor as its contractor nor its agent.
  Correspondingly, it is understood and agreed that the U.S. Government
  has no connection to this software and in no manner whatsoever shall
  be liable for nor assume any responsibility or obligation for any claim,
  cost, or damages arising out of or resulting from the use of the software
  available from this server.


  Export Control:

  All documents and software available from this server are subject to U.S.
  export control laws.  Anyone downloading information from this server is
  obligated to secure any necessary Government licenses before exporting
  documents or software obtained from this server.
 */
/*
 * Logback: the reliable, generic, fast and flexible logging framework.
  Copyright (C) 1999-2013, QOS.ch. All rights reserved.

  This program and the accompanying materials are dual-licensed under
  either the terms of the Eclipse Public License v1.0 as published by
  the Eclipse Foundation

     or (per the licensee's choosing)

   under the terms of the GNU Lesser General Public License version 2.1
   as published by the Free Software Foundation.
 */
package org.dcache.alarms.logback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ServerSocketFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This implementation adapts {@link ch.qos.logback.classic.net.SimpleSocketServer}
 * to run directly as a dCache cell component and to bypass re-entry of
 * the remotely sent logging event into the logging context.
 * <p>
 * This is achieved by a special implementation of the ServerSocketNode
 * which calls the LogEntryHandler (was LogEntryAppender) directly.
 *
 * @author arossi
 * @author Ceki G&uuml;lc&uuml;
 * @author S&eacute;bastien Pennec
 */
public final class LogEntryServer implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogEntryServer.class);

    private final List<LogEntryServerSocketNode> socketNodeList
        = Collections.synchronizedList(new ArrayList<>());
    private final Lock lock = new ReentrantLock();

    private LogEntryHandler handler;
    private Integer port;
    private ServerSocket serverSocket;
    private boolean running = false;

    public LogEntryHandler getHandler() {
        return handler;
    }

    public void run() {
        LOGGER.info("Listening on port {}.", port);

        try {
            serverSocket = ServerSocketFactory.getDefault()
                                              .createServerSocket(port);
        } catch (IOException t) {
            LOGGER.error("There was a problem creating the server socket: {}; "
                            + "cause {}.",  t.getMessage(), t.getCause());
            stop();
            return;
        }

        while (true) {
            lock.lock();
            try {
                if (!running) {
                    break;
                }

                LOGGER.debug("Waiting to accept a new client.");
                Socket socket = serverSocket.accept();
                LOGGER.debug("Connected to client at {}.",
                              socket.getInetAddress());
                LOGGER.debug("Starting new socket node.");
                LogEntryServerSocketNode newSocketNode
                    = new LogEntryServerSocketNode(this, socket);
                socketNodeList.add(newSocketNode);
                new Thread(newSocketNode).start();
            } catch (IOException t) {
                LOGGER.error("There was a problem connecting to client: {}; "
                                + "cause {}.",  t.getMessage(), t.getCause());
            } finally {
                lock.unlock();
            }
        }
    }

    public void setHandler(LogEntryHandler appender) {
        this.handler = checkNotNull(appender);
    }

    public void setPort(Integer port) {
        checkArgument(port != null && port > 0);
        this.port = port;
    }

    public void socketNodeClosing(LogEntryServerSocketNode sn) {
        LOGGER.debug("Removing {}.", sn);
        socketNodeList.remove(sn);
    }

    public void start() {
        lock.lock();
        try {
            if (!running) {
                running = true;
                new Thread(this).start();
            }
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        lock.lock();
        try {
            if (running) {
                running = false;
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        LOGGER.error("Failed to close serverSocket: {}.",
                                        e.getMessage());
                    } finally {
                        serverSocket = null;
                    }
                }

                LOGGER.debug("closing {}.", this);
                for (LogEntryServerSocketNode sn : socketNodeList) {
                    sn.close();
                }

                if (socketNodeList.size() != 0) {
                    LOGGER.debug("Was expecting an empty socket node list "
                                    + "after server shutdown, but list is {}.",
                                    socketNodeList);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
