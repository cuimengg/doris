// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.fs.remote;

import org.apache.doris.analysis.StorageBackend;
import org.apache.doris.backup.Status;
import org.apache.doris.common.UserException;
import org.apache.doris.common.security.authentication.AuthenticationConfig;
import org.apache.doris.common.security.authentication.HadoopAuthenticator;
import org.apache.doris.datasource.property.PropertyConverter;
import org.apache.doris.datasource.property.constants.S3Properties;
import org.apache.doris.fs.obj.S3ObjStorage;
import org.apache.doris.fs.remote.dfs.DFSFileSystem;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class S3FileSystem extends ObjFileSystem {

    private static final Logger LOG = LogManager.getLogger(S3FileSystem.class);
    private HadoopAuthenticator authenticator = null;

    public S3FileSystem(Map<String, String> properties) {
        super(StorageBackend.StorageType.S3.name(), StorageBackend.StorageType.S3, new S3ObjStorage(properties));
        initFsProperties();
    }

    @VisibleForTesting
    public S3FileSystem(S3ObjStorage storage) {
        super(StorageBackend.StorageType.S3.name(), StorageBackend.StorageType.S3, storage);
        initFsProperties();
    }

    private void initFsProperties() {
        this.properties.putAll(((S3ObjStorage) objStorage).getProperties());
    }

    @Override
    protected FileSystem nativeFileSystem(String remotePath) throws UserException {
        //todo Extracting a common method to achieve logic reuse
        if (closed.get()) {
            throw new UserException("FileSystem is closed.");
        }
        if (dfsFileSystem == null) {
            synchronized (this) {
                if (closed.get()) {
                    throw new UserException("FileSystem is closed.");
                }
                if (dfsFileSystem == null) {
                    Configuration conf = DFSFileSystem.getHdfsConf(ifNotSetFallbackToSimpleAuth());
                    System.setProperty("com.amazonaws.services.s3.enableV4", "true");
                    // the entry value in properties may be null, and
                    PropertyConverter.convertToHadoopFSProperties(properties).entrySet().stream()
                            .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                            .forEach(entry -> conf.set(entry.getKey(), entry.getValue()));
                    // S3 does not support Kerberos authentication,
                    // so here we create a simple authentication
                    AuthenticationConfig authConfig = AuthenticationConfig.getSimpleAuthenticationConfig(conf);
                    HadoopAuthenticator authenticator = HadoopAuthenticator.getHadoopAuthenticator(authConfig);
                    try {
                        dfsFileSystem = authenticator.doAs(() -> {
                            try {
                                return FileSystem.get(new Path(remotePath).toUri(), conf);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                        this.authenticator = authenticator;
                        RemoteFSPhantomManager.registerPhantomReference(this);
                    } catch (Exception e) {
                        throw new UserException("Failed to get S3 FileSystem for " + e.getMessage(), e);
                    }
                }
            }
        }
        return dfsFileSystem;
    }

    // broker file pattern glob is too complex, so we use hadoop directly
    private Status globListImplV1(String remotePath, List<RemoteFile> result, boolean fileNameOnly) {
        try {
            FileSystem s3AFileSystem = nativeFileSystem(remotePath);
            Path pathPattern = new Path(remotePath);
            FileStatus[] files = s3AFileSystem.globStatus(pathPattern);
            if (files == null) {
                return Status.OK;
            }
            for (FileStatus fileStatus : files) {
                RemoteFile remoteFile = new RemoteFile(
                        fileNameOnly ? fileStatus.getPath().getName() : fileStatus.getPath().toString(),
                        !fileStatus.isDirectory(), fileStatus.isDirectory() ? -1 : fileStatus.getLen(),
                        fileStatus.getBlockSize(), fileStatus.getModificationTime());
                result.add(remoteFile);
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("remotePath:{}, result:{}", remotePath, result);
            }

        } catch (FileNotFoundException e) {
            LOG.info("file not found: " + e.getMessage());
            return new Status(Status.ErrCode.NOT_FOUND, "file not found: " + e.getMessage());
        } catch (Exception e) {
            if (e.getCause() instanceof AmazonS3Exception) {
                // process minio error msg
                AmazonS3Exception ea = (AmazonS3Exception) e.getCause();
                Map<String, String> callbackHeaders = ea.getHttpHeaders();
                if (callbackHeaders != null && !callbackHeaders.isEmpty()) {
                    String minioErrMsg = callbackHeaders.get("X-Minio-Error-Desc");
                    if (minioErrMsg != null) {
                        return new Status(Status.ErrCode.COMMON_ERROR, "Minio request error: " + minioErrMsg);
                    }
                }
            }
            LOG.error("errors while get file status ", e);
            return new Status(Status.ErrCode.COMMON_ERROR, "errors while get file status " + e.getMessage());
        }
        return Status.OK;
    }

    private Status globListImplV2(String remotePath, List<RemoteFile> result, boolean fileNameOnly) {
        return ((S3ObjStorage) objStorage).globList(remotePath, result, fileNameOnly);
    }

    @Override
    public Status globList(String remotePath, List<RemoteFile> result, boolean fileNameOnly) {
        if (!Strings.isNullOrEmpty(properties.get(S3Properties.ROLE_ARN))
                || !Strings.isNullOrEmpty(properties.get(S3Properties.Env.ROLE_ARN))) {
            // https://issues.apache.org/jira/browse/HADOOP-19201
            // hadoop 3.3.6 we used now, not support aws assumed role with external id, so we
            // write a globListImplV2 to support it
            LOG.info("aws role arn mode, use globListImplV2");
            return globListImplV2(remotePath, result, fileNameOnly);
        }

        return globListImplV1(remotePath, result, fileNameOnly);
    }

    @VisibleForTesting
    public HadoopAuthenticator getAuthenticator() {
        return authenticator;
    }
}
