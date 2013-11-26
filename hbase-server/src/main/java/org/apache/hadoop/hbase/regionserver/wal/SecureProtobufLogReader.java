/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.regionserver.wal;

import java.io.IOException;
import java.security.Key;
import java.security.KeyException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.io.crypto.Cipher;
import org.apache.hadoop.hbase.io.crypto.Decryptor;
import org.apache.hadoop.hbase.io.crypto.Encryption;
import org.apache.hadoop.hbase.protobuf.generated.WALProtos.WALHeader;
import org.apache.hadoop.hbase.security.EncryptionUtil;
import org.apache.hadoop.hbase.security.User;

public class SecureProtobufLogReader extends ProtobufLogReader {

  private static final Log LOG = LogFactory.getLog(SecureProtobufLogReader.class);

  private Decryptor decryptor = null;

  @Override
  protected boolean readHeader(WALHeader.Builder builder, FSDataInputStream stream)
      throws IOException {
    boolean result = super.readHeader(builder, stream);
    // We need to unconditionally handle the case where the WAL has a key in
    // the header, meaning it is encrypted, even if ENABLE_WAL_ENCRYPTION is
    // no longer set in the site configuration.
    if (result && builder.hasEncryptionKey()) {
      // Serialized header data has been merged into the builder from the
      // stream.

      // Retrieve a usable key

      byte[] keyBytes = builder.getEncryptionKey().toByteArray();
      Key key = null;
      String walKeyName = conf.get(HConstants.CRYPTO_WAL_KEY_NAME_CONF_KEY);
      // First try the WAL key, if one is configured
      if (walKeyName != null) {
        try {
          key = EncryptionUtil.unwrapKey(conf, walKeyName, keyBytes);
        } catch (KeyException e) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Unable to unwrap key with WAL key '" + walKeyName + "'");
          }
          key = null;
        }
      }
      if (key == null) {
        String masterKeyName = conf.get(HConstants.CRYPTO_MASTERKEY_NAME_CONF_KEY,
          User.getCurrent().getShortName());
        try {
          // Then, try the cluster master key
          key = EncryptionUtil.unwrapKey(conf, masterKeyName, keyBytes);
        } catch (KeyException e) {
          // If the current master key fails to unwrap, try the alternate, if
          // one is configured
          if (LOG.isDebugEnabled()) {
            LOG.debug("Unable to unwrap key with current master key '" + masterKeyName + "'");
          }
          String alternateKeyName =
            conf.get(HConstants.CRYPTO_MASTERKEY_ALTERNATE_NAME_CONF_KEY);
          if (alternateKeyName != null) {
            try {
              key = EncryptionUtil.unwrapKey(conf, alternateKeyName, keyBytes);
            } catch (KeyException ex) {
              throw new IOException(ex);
            }
          } else {
            throw new IOException(e);
          }
        }
      }

      // Use the algorithm the key wants

      Cipher cipher = Encryption.getCipher(conf, key.getAlgorithm());
      if (cipher == null) {
        throw new IOException("Cipher '" + key.getAlgorithm() + "' is not available");
      }

      // Set up the decryptor for this WAL

      decryptor = cipher.getDecryptor();
      decryptor.setKey(key);

      if (LOG.isTraceEnabled()) {
        LOG.trace("Initialized secure protobuf WAL: cipher=" + cipher.getName());
      }
    }

    return result;
  }

  @Override
  protected void initAfterCompression() throws IOException {
    WALCellCodec codec = SecureWALCellCodec.getCodec(this.conf, decryptor);
    this.cellDecoder = codec.getDecoder(this.inputStream);
    // We do not support compression
    this.compressionContext = null;
    this.hasCompression = false;
  }

}
