/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.jdbc.store.file;

import java.io.File;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.apache.activemq.artemis.core.io.IOCriticalErrorListener;
import org.apache.activemq.artemis.core.io.SequentialFile;
import org.apache.activemq.artemis.core.io.SequentialFileFactory;
import org.apache.activemq.artemis.core.io.nio.NIOSequentialFileFactory;
import org.apache.activemq.artemis.core.server.ActiveMQComponent;
import org.apache.activemq.artemis.jdbc.store.drivers.JDBCConnectionProvider;
import org.apache.activemq.artemis.jdbc.store.sql.SQLProvider;
import org.apache.activemq.artemis.utils.collections.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.invoke.MethodHandles;

public class JDBCSequentialFileFactory implements SequentialFileFactory, ActiveMQComponent {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private boolean started;

   private final Set<JDBCSequentialFile> files = new ConcurrentHashSet<>();

   private final Executor executor;

   private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();

   private JDBCSequentialFileFactoryDriver dbDriver;

   private final IOCriticalErrorListener criticalErrorListener;

   public JDBCSequentialFileFactory(final JDBCConnectionProvider connectionProvider,
                                    final SQLProvider sqlProvider,
                                    Executor executor,
                                    IOCriticalErrorListener criticalErrorListener) throws Exception {

      this.executor = executor;
      this.criticalErrorListener = criticalErrorListener;

      try {
         this.dbDriver = JDBCFileUtils.getDBFileDriver(connectionProvider, sqlProvider);
      } catch (SQLException e) {
         criticalErrorListener.onIOException(e, "Failed to start JDBC Driver", null);
      }
   }

   public JDBCSequentialFileFactoryDriver getDbDriver() {
      return dbDriver;
   }

   @Override
   public SequentialFileFactory setDatasync(boolean enabled) {
      return this;
   }

   @Override
   public boolean isDatasync() {
      return false;
   }

   @Override
   public long getBufferSize() {
      return dbDriver.getMaxSize();
   }

   @Override
   public synchronized void start() {
      try {
         if (!started) {
            dbDriver.start();
            started = true;
         }
      } catch (Exception e) {
         criticalErrorListener.onIOException(e, "Unable to start database driver", null);
         started = false;
      }
   }

   @Override
   public synchronized void stop() {
      try {
         dbDriver.stop();
      } catch (SQLException e) {
         logger.error("Error stopping file factory, unable to close db connection");
      }
      started = false;
   }

   @Override
   public SequentialFile createSequentialFile(String fileName) {
      try {
         fileLocks.putIfAbsent(fileName, new Object());
         JDBCSequentialFile file = new JDBCSequentialFile(this, fileName, executor, dbDriver, fileLocks.get(fileName));
         files.add(file);
         return file;
      } catch (Exception e) {
         criticalErrorListener.onIOException(e, "Error whilst creating JDBC file", null);
      }
      return null;
   }

   public void sequentialFileClosed(SequentialFile file) {
      files.remove(file);
   }

   public int getNumberOfOpenFiles() {
      return files.size();
   }

   @Override
   public int getMaxIO() {
      return 1;
   }

   @Override
   public List<String> listFiles(String extension) throws Exception {
      try {
         return dbDriver.listFiles(extension);
      } catch (SQLException e) {
         // We can't throw critical error here
         // exists will call listfiles, and if the store does not exists
         // it should simply return false
         throw e;
      }
   }

   @Override
   public boolean isSupportsCallbacks() {
      return true;
   }

   @Override
   public void onIOError(Throwable exception, String message, String file) {
      criticalErrorListener.onIOException(exception, message, file);
   }

   @Override
   public ByteBuffer allocateDirectBuffer(final int size) {
      return NIOSequentialFileFactory.allocateDirectByteBuffer(size);
   }

   @Override
   public void releaseDirectBuffer(ByteBuffer buffer) {
      // nothing we can do on this case. we can just have good faith on GC
   }

   @Override
   public ByteBuffer newBuffer(final int size) {
      return ByteBuffer.allocate(size);
   }

   @Override
   public void clearBuffer(final ByteBuffer buffer) {
      final int limit = buffer.limit();
      buffer.rewind();

      for (int i = 0; i < limit; i++) {
         buffer.put((byte) 0);
      }

      buffer.rewind();
   }

   @Override
   public ByteBuffer wrapBuffer(final byte[] bytes) {
      return ByteBuffer.wrap(bytes);
   }

   @Override
   public int getAlignment() {
      return 1;
   }

   @Override
   public JDBCSequentialFileFactory setAlignment(int alignment) {
      // no op
      return this;
   }

   @Override
   public int calculateBlockSize(final int bytes) {
      return bytes;
   }

   @Override
   public void deactivateBuffer() {
   }

   @Override
   public void releaseBuffer(final ByteBuffer buffer) {
   }

   @Override
   public void activateBuffer(SequentialFile file) {
   }

   @Override
   public File getDirectory() {
      return null;
   }

   @Override
   public boolean isStarted() {
      return started;
   }

   @Override
   public void createDirs() throws Exception {
   }

   @Override
   public void flush() {
      for (SequentialFile file : files) {
         try {
            file.sync();
         } catch (Exception e) {
            criticalErrorListener.onIOException(e, "Error during JDBC file sync.", file.getFileName());
         }
      }
   }

   public synchronized void destroy() throws SQLException {
      try {
         dbDriver.destroy();
      } catch (SQLException e) {
         logger.error("Error destroying file factory", e);
      }
   }
}
