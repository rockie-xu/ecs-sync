/*
 * Copyright 2013 EMC Corporation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.emc.vipr.sync.target;

import com.emc.atmos.AtmosException;
import com.emc.atmos.api.*;
import com.emc.atmos.api.bean.Metadata;
import com.emc.atmos.api.bean.ObjectMetadata;
import com.emc.atmos.api.bean.ServiceInformation;
import com.emc.atmos.api.jersey.AtmosApiClient;
import com.emc.atmos.api.request.CreateObjectRequest;
import com.emc.atmos.api.request.UpdateObjectRequest;
import com.emc.vipr.sync.filter.SyncFilter;
import com.emc.vipr.sync.model.AtmosMetadata;
import com.emc.vipr.sync.model.SyncMetadata;
import com.emc.vipr.sync.model.SyncObject;
import com.emc.vipr.sync.source.SyncSource;
import com.emc.vipr.sync.util.*;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.log4j.LogMF;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Stores objects into an Atmos system.
 *
 * @author cwikj
 */
public class AtmosTarget extends SyncTarget {
    /**
     * This pattern is used to activate this plugin.
     */
    public static final String DEST_NO_UPDATE_OPTION = "no-update";
    public static final String DEST_NO_UPDATE_DESC = "If specified, no updates will be applied to the target";

    public static final String DEST_CHECKSUM_OPT = "target-checksum";
    public static final String DEST_CHECKSUM_DESC = "If specified, the atmos wschecksum feature will be applied to uploads.  Valid algorithms are SHA0 for Atmos < 2.1 and SHA0, SHA1, or MD5 for 2.1+";
    public static final String DEST_CHECKSUM_ARG_NAME = "checksum-alg";

    public static final String RETENTION_DELAY_WINDOW_OPTION = "retention-delay-window";
    public static final String RETENTION_DELAY_WINDOW_DESC = "If include-retention-expiration is set, use this option to specify the Start Delay Window in the retention policy.  Default is 1 second (the minimum).";
    public static final String RETENTION_DELAY_WINDOW_ARG_NAME = "seconds";
    // timed operations
    private static final String OPERATION_SET_USER_META = "AtmosSetUserMeta";
    private static final String OPERATION_SET_ACL = "AtmosSetAcl";
    private static final String OPERATION_CREATE_OBJECT = "AtmosCreateObject";
    private static final String OPERATION_CREATE_OBJECT_ON_PATH = "AtmosCreateObjectOnPath";
    private static final String OPERATION_CREATE_OBJECT_FROM_SEGMENT = "AtmosCreateObjectFromSegment";
    private static final String OPERATION_CREATE_OBJECT_FROM_SEGMENT_ON_PATH = "AtmosCreateObjectFromSegmentOnPath";
    private static final String OPERATION_UPDATE_OBJECT_FROM_SEGMENT = "AtmosUpdateObjectFromSegment";
    private static final String OPERATION_CREATE_OBJECT_FROM_STREAM = "AtmosCreateObjectFromStream";
    private static final String OPERATION_CREATE_OBJECT_FROM_STREAM_ON_PATH = "AtmosCreateObjectFromStreamOnPath";
    private static final String OPERATION_DELETE_OBJECT = "AtmosDeleteObject";
    private static final String OPERATION_SET_RETENTION_EXPIRATION = "AtmosSetRetentionExpiration";
    private static final String OPERATION_GET_ALL_META = "AtmosGetAllMeta";
    private static final String OPERATION_GET_SYSTEM_META = "AtmosGetSystemMeta";
    private static final String OPERATION_TOTAL = "TotalTime";

    private static final Logger l4j = Logger.getLogger(AtmosTarget.class);

    private List<URI> endpoints;
    private String uid;
    private String secret;
    private AtmosApi atmos;
    private String destNamespace;
    private boolean noUpdate;
    private long retentionDelayWindow = 1; // 1 second by default
    private String checksum;

    @Override
    public boolean canHandleTarget(String targetUri) {
        return targetUri.startsWith(AtmosUtil.URI_PREFIX);
    }

    @Override
    public Options getCustomOptions() {
        Options opts = new Options();
        opts.addOption(new OptionBuilder().withLongOpt(DEST_NO_UPDATE_OPTION).withDescription(DEST_NO_UPDATE_DESC).create());
        opts.addOption(new OptionBuilder().withLongOpt(DEST_CHECKSUM_OPT).withDescription(DEST_CHECKSUM_DESC)
                .hasArg().withArgName(DEST_CHECKSUM_ARG_NAME).create());
        opts.addOption(new OptionBuilder().withLongOpt(RETENTION_DELAY_WINDOW_OPTION).withDescription(RETENTION_DELAY_WINDOW_DESC)
                .hasArg().withArgName(RETENTION_DELAY_WINDOW_ARG_NAME).create());
        return opts;
    }

    @Override
    protected void parseCustomOptions(CommandLine line) {
        AtmosUtil.AtmosUri atmosUri = AtmosUtil.parseUri(targetUri);
        endpoints = atmosUri.endpoints;
        uid = atmosUri.uid;
        secret = atmosUri.secret;
        destNamespace = atmosUri.rootPath;

        if (line.hasOption(DEST_NO_UPDATE_OPTION))
            noUpdate = true;

        if (line.hasOption(DEST_CHECKSUM_OPT))
            checksum = line.getOptionValue(DEST_CHECKSUM_OPT);

        if (line.hasOption(RETENTION_DELAY_WINDOW_OPTION))
            retentionDelayWindow = Long.parseLong(line.getOptionValue(RETENTION_DELAY_WINDOW_OPTION));
    }

    @Override
    public void configure(SyncSource source, Iterator<SyncFilter> filters, SyncTarget target) {
        if (atmos == null) {
            if (endpoints == null || uid == null || secret == null)
                throw new ConfigurationException("Must specify endpoints, uid and secret key");
            atmos = new AtmosApiClient(new AtmosConfig(uid, secret, endpoints.toArray(new URI[endpoints.size()])));
        }

        // Check authentication
        ServiceInformation info = atmos.getServiceInformation();
        LogMF.info(l4j, "Connected to Atmos {0} on {1}", info.getAtmosVersion(), endpoints);

        if (noUpdate)
            l4j.info("Overwrite/update target objects disabled");

        if (includeRetentionExpiration)
            l4j.info("Retention start delay window set to " + retentionDelayWindow);
    }

    @Override
    public void filter(final SyncObject<?> obj) {
        // skip the root namespace since it obviously exists
        if ("/".equals(destNamespace + obj.getRelativePath())) {
            l4j.debug("Target namespace is root");
            return;
        }

        timeOperationStart(OPERATION_TOTAL);
        try {
            // some sync objects lazy-load their metadata (i.e. AtmosSyncObject)
            // since this may be a timed operation, ensure it loads outside of other timed operations
            final Map<String, Metadata> umeta = getAtmosUserMetadata(obj.getMetadata());

            if (destNamespace != null) {
                // Determine a name for the object.
                ObjectPath destPath;
                if (!destNamespace.endsWith("/")) {
                    // A specific file was mentioned.
                    destPath = new ObjectPath(destNamespace);
                } else {
                    String path = destNamespace + obj.getRelativePath();
                    if (obj.hasChildren() && !path.endsWith("/")) path += "/";
                    destPath = new ObjectPath(path);
                }
                final ObjectPath fDestPath = destPath;

                obj.setTargetIdentifier(destPath.toString());

                // See if the target exists
                if (destPath.isDirectory()) {
                    Map<String, Metadata> smeta = getSystemMetadata(destPath);

                    if (smeta != null) {
                        // See if a metadata update is required
                        Date srcCtime = parseDate(obj.getMetadata().getSystemMetadataProp("ctime"));
                        Date dstCtime = parseDate(smeta.get("ctime"));

                        if ((srcCtime != null && dstCtime != null && srcCtime.after(dstCtime)) || force) {
                            if (umeta != null && umeta.size() > 0) {
                                LogMF.debug(l4j, "Updating metadata on {0}", destPath);
                                time(new Timeable<Void>() {
                                    @Override
                                    public Void call() {
                                        atmos.setUserMetadata(fDestPath, umeta.values().toArray(new Metadata[umeta.size()]));
                                        return null;
                                    }
                                }, OPERATION_SET_USER_META);
                            }
                            final Acl acl = getAtmosAcl(obj.getMetadata());
                            if (acl != null) {
                                LogMF.debug(l4j, "Updating ACL on {0}", destPath);
                                time(new Timeable<Void>() {
                                    @Override
                                    public Void call() {
                                        atmos.setAcl(fDestPath, acl);
                                        return null;
                                    }
                                }, OPERATION_SET_ACL);
                            }
                        } else {
                            LogMF.debug(l4j, "No changes from source {0} to dest {1}",
                                    obj.getSourceIdentifier(),
                                    obj.getTargetIdentifier());
                            return;
                        }
                    } else {
                        // Directory does not exist on target
                        time(new Timeable<ObjectId>() {
                            @Override
                            public ObjectId call() {
                                return atmos.createDirectory(fDestPath, getAtmosAcl(obj.getMetadata()),
                                        umeta.values().toArray(new Metadata[umeta.size()]));
                            }
                        }, OPERATION_CREATE_OBJECT_ON_PATH);
                    }

                } else {
                    // File, not directory
                    ObjectMetadata destMeta = getMetadata(destPath);
                    if (destMeta == null) {
                        // Target does not exist.
                        InputStream in = null;
                        try {
                            in = obj.getInputStream();
                            ObjectId id = null;
                            if (in == null) {
                                // Create an empty object
                                final CreateObjectRequest request = new CreateObjectRequest();
                                request.identifier(destPath).acl(getAtmosAcl(obj.getMetadata()));
                                request.setUserMetadata(umeta.values());
                                request.contentType(obj.getMetadata().getContentType());
                                id = time(new Timeable<ObjectId>() {
                                    @Override
                                    public ObjectId call() {
                                        return atmos.createObject(request).getObjectId();
                                    }
                                }, OPERATION_CREATE_OBJECT_ON_PATH);
                            } else {
                                if (checksum != null) {
                                    final RunningChecksum ck = new RunningChecksum(ChecksumAlgorithm.valueOf(checksum));
                                    byte[] buffer = new byte[1024 * 1024];
                                    long read = 0;
                                    int c;
                                    while ((c = in.read(buffer)) != -1) {
                                        final BufferSegment bs = new BufferSegment(buffer, 0, c);
                                        if (read == 0) {
                                            // Create
                                            ck.update(bs.getBuffer(), bs.getOffset(), bs.getSize());
                                            final CreateObjectRequest request = new CreateObjectRequest();
                                            request.identifier(destPath).acl(getAtmosAcl(obj.getMetadata())).content(bs);
                                            request.setUserMetadata(umeta.values());
                                            request.contentType(obj.getMetadata().getContentType()).wsChecksum(ck);
                                            id = time(new Timeable<ObjectId>() {
                                                @Override
                                                public ObjectId call() {
                                                    return atmos.createObject(request).getObjectId();
                                                }
                                            }, OPERATION_CREATE_OBJECT_FROM_SEGMENT_ON_PATH);
                                        } else {
                                            // Append
                                            ck.update(bs.getBuffer(), bs.getOffset(), bs.getSize());
                                            Range r = new Range(read, read + c - 1);
                                            final UpdateObjectRequest request = new UpdateObjectRequest();
                                            request.identifier(id).acl(getAtmosAcl(obj.getMetadata())).content(bs).range(r);
                                            request.setUserMetadata(umeta.values());
                                            request.contentType(obj.getMetadata().getContentType()).wsChecksum(ck);
                                            time(new Timeable<Object>() {
                                                @Override
                                                public Object call() {
                                                    atmos.updateObject(request);
                                                    return null;
                                                }
                                            }, OPERATION_UPDATE_OBJECT_FROM_SEGMENT);
                                        }
                                        read += c;
                                    }
                                } else {
                                    final CreateObjectRequest request = new CreateObjectRequest();
                                    request.identifier(destPath).acl(getAtmosAcl(obj.getMetadata())).content(in);
                                    request.setUserMetadata(umeta.values());
                                    request.contentLength(obj.getSize()).contentType(obj.getMetadata().getContentType());
                                    id = time(new Timeable<ObjectId>() {
                                        @Override
                                        public ObjectId call() {
                                            return atmos.createObject(request).getObjectId();
                                        }
                                    }, OPERATION_CREATE_OBJECT_FROM_STREAM_ON_PATH);
                                }
                            }

                            updateRetentionExpiration(obj, id);
                        } finally {
                            if (in != null) {
                                in.close();
                            }
                        }

                    } else {
                        checkUpdate(obj, destPath, destMeta);
                    }
                }
            } else {
                // Object Space

                // don't create objects in objectspace with no data (likely directories from a filesystem source)
                // note that files/objects with zero size are still considered to have data.
                // TODO: is this a valid use-case (should we create these objects)?
                if (!obj.hasData()) {
                    LogMF.debug(l4j, "Source {0} is not a data object, but target is in objectspace, ignoring",
                            obj.getSourceIdentifier());
                    return;
                }

                InputStream in = null;
                try {
                    ObjectId id = null;
                    // Check and see if a target ID was alredy computed
                    String targetId = obj.getTargetIdentifier();
                    if (targetId != null) {
                        id = new ObjectId(targetId);
                    }

                    if (id != null) {
                        ObjectMetadata destMeta = getMetadata(id);
                        if (destMeta == null) {
                            // Target ID not found!
                            throw new RuntimeException("The target object ID " + id + " was not found!");
                        }
                        obj.setTargetIdentifier(id.toString());
                        checkUpdate(obj, id, destMeta);
                    } else {
                        in = obj.getInputStream();
                        if (in == null) {
                            // Usually some sort of directory
                            final CreateObjectRequest request = new CreateObjectRequest();
                            request.acl(getAtmosAcl(obj.getMetadata())).contentType(obj.getMetadata().getContentType());
                            request.setUserMetadata(umeta.values());
                            id = time(new Timeable<ObjectId>() {
                                @Override
                                public ObjectId call() {
                                    return atmos.createObject(request).getObjectId();
                                }
                            }, OPERATION_CREATE_OBJECT);
                        } else {
                            if (checksum != null) {
                                final RunningChecksum ck = new RunningChecksum(ChecksumAlgorithm.valueOf(checksum));
                                byte[] buffer = new byte[1024 * 1024];
                                long read = 0;
                                int c;
                                while ((c = in.read(buffer)) != -1) {
                                    final BufferSegment bs = new BufferSegment(buffer, 0, c);
                                    if (read == 0) {
                                        // Create
                                        ck.update(bs.getBuffer(), bs.getOffset(), bs.getSize());
                                        final CreateObjectRequest request = new CreateObjectRequest();
                                        request.acl(getAtmosAcl(obj.getMetadata())).content(bs);
                                        request.setUserMetadata(umeta.values());
                                        request.contentType(obj.getMetadata().getContentType()).wsChecksum(ck);
                                        id = time(new Timeable<ObjectId>() {
                                            @Override
                                            public ObjectId call() {
                                                return atmos.createObject(request).getObjectId();
                                            }
                                        }, OPERATION_CREATE_OBJECT_FROM_SEGMENT);
                                    } else {
                                        // Append
                                        ck.update(bs.getBuffer(), bs.getOffset(), bs.getSize());
                                        Range r = new Range(read, read + c - 1);
                                        final UpdateObjectRequest request = new UpdateObjectRequest();
                                        request.identifier(id).acl(getAtmosAcl(obj.getMetadata())).content(bs).range(r);
                                        request.setUserMetadata(umeta.values());
                                        request.contentType(obj.getMetadata().getContentType()).wsChecksum(ck);
                                        time(new Timeable<Void>() {
                                            @Override
                                            public Void call() {
                                                atmos.updateObject(request);
                                                return null;
                                            }
                                        }, OPERATION_UPDATE_OBJECT_FROM_SEGMENT);
                                    }
                                    read += c;
                                }
                            } else {
                                final CreateObjectRequest request = new CreateObjectRequest();
                                request.acl(getAtmosAcl(obj.getMetadata())).content(in);
                                request.setUserMetadata(umeta.values());
                                request.contentLength(obj.getSize()).contentType(obj.getMetadata().getContentType());
                                id = time(new Timeable<ObjectId>() {
                                    @Override
                                    public ObjectId call() {
                                        return atmos.createObject(request).getObjectId();
                                    }
                                }, OPERATION_CREATE_OBJECT_FROM_STREAM);
                            }
                        }

                        updateRetentionExpiration(obj, id);

                        obj.setTargetIdentifier(id == null ? null : id.toString());
                    }
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                }

            }
            LogMF.debug(l4j, "Wrote source {0} to dest {1}", obj.getSourceIdentifier(), obj.getTargetIdentifier());

            timeOperationComplete(OPERATION_TOTAL);
        } catch (Exception e) {
            timeOperationFailed(OPERATION_TOTAL);
            throw new RuntimeException(
                    "Failed to store object: " + e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return "Atmos Target";
    }

    @Override
    public String getDocumentation() {
        return "The Atmos target plugin is triggered by the target pattern:\n" +
                AtmosUtil.PATTERN_DESC + "\n" +
                "Note that the uid should be the 'full token ID' including the " +
                "subtenant ID and the uid concatenated by a slash\n" +
                "If you want to software load balance across multiple hosts, " +
                "you can provide a comma-delimited list of hostnames or IPs " +
                "in the host part of the URI.\n" +
                "By default, objects will be written to Atmos using the " +
                "object API unless namespace-path is specified.\n" +
                "When namespace-path is used, the --force flag may be used " +
                "to overwrite target objects even if they exist.";
    }

    private Map<String, Metadata> getAtmosUserMetadata(SyncMetadata metadata) {
        if (metadata instanceof AtmosMetadata)
            return ((AtmosMetadata) metadata).getMetadata();

        Map<String, Metadata> userMetadata = new HashMap<>();
        for (String key : metadata.getUserMetadataKeys()) {
            userMetadata.put(key, new Metadata(key, metadata.getUserMetadataProp(key), false));
        }
        return userMetadata;
    }

    private Acl getAtmosAcl(SyncMetadata metadata) {
        if (metadata instanceof AtmosMetadata)
            return ((AtmosMetadata) metadata).getAcl();

        // because of potential semantic conflicts, we can't support external ACL mapping in this plug-in
        // (it must be mapped in some other plug-in)
        return null;
    }

    /**
     * If the target exists, we perform some checks and update only what
     * needs to be updated (metadata and/or content)
     */
    private void checkUpdate(final SyncObject obj, final ObjectIdentifier destId, ObjectMetadata destMeta) throws IOException {
        SyncMetadata meta = obj.getMetadata();
        // Exists.  Check timestamps
        Date srcMtime = meta.getModifiedTime();
        Date dstMtime = parseDate(destMeta.getMetadata().get("mtime"));
        Date srcCtime = parseDate(meta.getSystemMetadataProp("ctime"));
        if (srcCtime == null) srcCtime = srcMtime;
        Date dstCtime = parseDate(destMeta.getMetadata().get("ctime"));
        if ((srcMtime != null && dstMtime != null && srcMtime.after(dstMtime)) || force) {
            if (noUpdate) {
                LogMF.debug(l4j, "Skipping {0}, updates disabled.", obj.getSourceIdentifier(), obj.getTargetIdentifier());
                return;
            }
            // Update the object
            InputStream in = null;
            try {
                in = obj.getInputStream();
                if (in == null) {
                    // Metadata only
                    final Map<String, Metadata> metaMap = getAtmosUserMetadata(obj.getMetadata());
                    if (metaMap != null && metaMap.size() > 0) {
                        LogMF.debug(l4j, "Updating metadata on {0}", destId);
                        time(new Timeable<Void>() {
                            @Override
                            public Void call() {
                                atmos.setUserMetadata(destId, metaMap.values().toArray(new Metadata[metaMap.size()]));
                                return null;
                            }
                        }, OPERATION_SET_USER_META);
                    }
                    if (getAtmosAcl(obj.getMetadata()) != null) {
                        LogMF.debug(l4j, "Updating ACL on {0}", destId);
                        time(new Timeable<Void>() {
                            @Override
                            public Void call() {
                                atmos.setAcl(destId, getAtmosAcl(obj.getMetadata()));
                                return null;
                            }
                        }, OPERATION_SET_ACL);
                    }
                } else {
                    LogMF.debug(l4j, "Updating {0}", destId);
                    if (checksum != null) {
                        try {
                            final RunningChecksum ck = new RunningChecksum(ChecksumAlgorithm.valueOf(checksum));
                            byte[] buffer = new byte[1024 * 1024];
                            long read = 0;
                            int c;
                            while ((c = in.read(buffer)) != -1) {
                                final BufferSegment bs = new BufferSegment(buffer, 0, c);
                                if (read == 0) {
                                    // You cannot update a checksummed object.
                                    // Delete and replace.
                                    if (destId instanceof ObjectId) {
                                        throw new RuntimeException(
                                                "Cannot update checksummed " +
                                                        "object by ObjectID, only " +
                                                        "namespace objects are " +
                                                        "supported");
                                    }
                                    time(new Timeable<Void>() {
                                        @Override
                                        public Void call() {
                                            atmos.delete(destId);
                                            return null;
                                        }
                                    }, OPERATION_DELETE_OBJECT);
                                    ck.update(bs.getBuffer(), bs.getOffset(), bs.getSize());
                                    final CreateObjectRequest request = new CreateObjectRequest();
                                    request.identifier(destId).acl(getAtmosAcl(obj.getMetadata())).content(bs);
                                    request.setUserMetadata(getAtmosUserMetadata(obj.getMetadata()).values());
                                    request.contentType(obj.getMetadata().getContentType()).wsChecksum(ck);
                                    time(new Timeable<Void>() {
                                        @Override
                                        public Void call() {
                                            atmos.createObject(request);
                                            return null;
                                        }
                                    }, OPERATION_CREATE_OBJECT_FROM_SEGMENT_ON_PATH);
                                } else {
                                    // Append
                                    ck.update(bs.getBuffer(), bs.getOffset(), bs.getSize());
                                    Range r = new Range(read, read + c - 1);
                                    final UpdateObjectRequest request = new UpdateObjectRequest();
                                    request.identifier(destId).acl(getAtmosAcl(obj.getMetadata())).content(bs).range(r);
                                    request.setUserMetadata(getAtmosUserMetadata(obj.getMetadata()).values());
                                    request.contentType(obj.getMetadata().getContentType()).wsChecksum(ck);
                                    time(new Timeable<Void>() {
                                        @Override
                                        public Void call() {
                                            atmos.updateObject(request);
                                            return null;
                                        }
                                    }, OPERATION_UPDATE_OBJECT_FROM_SEGMENT);
                                }
                                read += c;
                            }
                        } catch (NoSuchAlgorithmException e) {
                            throw new RuntimeException(
                                    "Incorrect checksum method: " + checksum,
                                    e);
                        }
                    } else {
                        final UpdateObjectRequest request = new UpdateObjectRequest();
                        request.identifier(destId).acl(getAtmosAcl(obj.getMetadata())).content(in);
                        request.setUserMetadata(getAtmosUserMetadata(obj.getMetadata()).values());
                        request.contentLength(obj.getSize()).contentType(obj.getMetadata().getContentType());
                        time(new Timeable<Void>() {
                            @Override
                            public Void call() {
                                atmos.updateObject(request);
                                return null;
                            }
                        }, OPERATION_UPDATE_OBJECT_FROM_SEGMENT);
                    }
                }

                // update retention/expiration in case policy changed
                updateRetentionExpiration(obj, destId);
            } finally {
                if (in != null) {
                    in.close();
                }
            }

        } else if (srcCtime != null && dstCtime != null && srcCtime.after(dstCtime)) {
            if (noUpdate) {
                LogMF.debug(l4j, "Skipping {0}, updates disabled.", obj.getSourceIdentifier(), obj.getTargetIdentifier());
                return;
            }
            // Metadata update required.
            final Map<String, Metadata> metaMap = getAtmosUserMetadata(obj.getMetadata());
            if (metaMap != null && metaMap.size() > 0) {
                LogMF.debug(l4j, "Updating metadata on {0}", destId);
                time(new Timeable<Void>() {
                    @Override
                    public Void call() {
                        atmos.setUserMetadata(destId, metaMap.values().toArray(new Metadata[metaMap.size()]));
                        return null;
                    }
                }, OPERATION_SET_USER_META);
            }
            if (getAtmosAcl(obj.getMetadata()) != null) {
                LogMF.debug(l4j, "Updating ACL on {0}", destId);
                time(new Timeable<Void>() {
                    @Override
                    public Void call() {
                        atmos.setAcl(destId, getAtmosAcl(obj.getMetadata()));
                        return null;
                    }
                }, OPERATION_SET_ACL);
            }

            // update retention/expiration in case policy changed
            updateRetentionExpiration(obj, destId);
        } else {
            // No updates
            LogMF.debug(l4j, "No changes from source {0} to dest {1}", obj.getSourceIdentifier(), obj.getTargetIdentifier());
        }
    }

    private void updateRetentionExpiration(final SyncObject obj, final ObjectIdentifier destId) {
        if (includeRetentionExpiration) {
            try {
                final List<Metadata> retExpList = AtmosUtil.getExpirationMetadataForUpdate(obj);
                retExpList.addAll(AtmosUtil.getRetentionMetadataForUpdate(obj));
                if (retExpList.size() > 0) {
                    time(new Timeable<Void>() {
                        @Override
                        public Void call() {
                            atmos.setUserMetadata(destId, retExpList.toArray(new Metadata[retExpList.size()]));
                            return null;
                        }
                    }, OPERATION_SET_RETENTION_EXPIRATION);
                }
            } catch (AtmosException e) {
                LogMF.error(l4j, "Failed to manually set retention/expiration\n" +
                        "(destId: {0}, retentionEnd: {1}, expiration: {2})\n" +
                        "[http: {3}, atmos: {4}, msg: {5}]", new Object[]{
                        destId, Iso8601Util.format(obj.getMetadata().getRetentionEndDate()),
                        Iso8601Util.format(obj.getMetadata().getExpirationDate()),
                        e.getHttpCode(), e.getErrorCode(), e.getMessage()});
            } catch (RuntimeException e) {
                LogMF.error(l4j, "Failed to manually set retention/expiration\n" +
                        "(destId: {0}, retentionEnd: {1}, expiration: {2})\n[error: {3}]", new Object[]{
                        destId, Iso8601Util.format(obj.getMetadata().getRetentionEndDate()),
                        Iso8601Util.format(obj.getMetadata().getExpirationDate()), e.getMessage()});
            }
        }
    }

    /**
     * Gets the metadata for an object.  IFF the object does not exist, null
     * is returned.  If any other error condition exists, the exception is
     * thrown.
     *
     * @param destId The object to get metadata for.
     * @return the object's metadata or null.
     */
    private ObjectMetadata getMetadata(final ObjectIdentifier destId) {
        try {
            return time(new Timeable<ObjectMetadata>() {
                @Override
                public ObjectMetadata call() {
                    return atmos.getObjectMetadata(destId);
                }
            }, OPERATION_GET_ALL_META);
        } catch (AtmosException e) {
            if (e.getHttpCode() == 404) {
                // Object not found
                return null;
            } else {
                // Some other error, rethrow it
                throw e;
            }
        }
    }

    /**
     * Tries to parse an ISO-8601 date out of a metadata value.  If the value
     * is null or the parse fails, null is returned.
     *
     * @param m the metadata value
     * @return the Date or null if a date could not be parsed from the value.
     */
    private Date parseDate(Metadata m) {
        if (m == null || m.getValue() == null) {
            return null;
        }
        return parseDate(m.getValue());
    }

    private Date parseDate(String s) {
        return Iso8601Util.parse(s);
    }

    /**
     * Get system metadata.  IFF the object doesn't exist, return null.  On any
     * other error (e.g. permission denied), throw exception.
     */
    private Map<String, Metadata> getSystemMetadata(final ObjectPath destPath) {
        try {
            return time(new Timeable<Map<String, Metadata>>() {
                @Override
                public Map<String, Metadata> call() {
                    return atmos.getSystemMetadata(destPath);
                }
            }, OPERATION_GET_SYSTEM_META);
        } catch (AtmosException e) {
            if (e.getErrorCode() == 1003) {
                // Object not found --OK
                return null;
            } else {
                throw new RuntimeException(
                        "Error checking for object existance: " +
                                e.getMessage(), e);
            }
        }
    }

    public String getDestNamespace() {
        return destNamespace;
    }

    public void setDestNamespace(String destNamespace) {
        this.destNamespace = destNamespace;
    }

    public List<URI> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<URI> endpoints) {
        this.endpoints = endpoints;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public boolean isNoUpdate() {
        return noUpdate;
    }

    public void setNoUpdate(boolean noUpdate) {
        this.noUpdate = noUpdate;
    }

    public long getRetentionDelayWindow() {
        return retentionDelayWindow;
    }

    public void setRetentionDelayWindow(long retentionDelayWindow) {
        this.retentionDelayWindow = retentionDelayWindow;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    /**
     * @return the atmos
     */
    public AtmosApi getAtmos() {
        return atmos;
    }

    /**
     * @param atmos the atmos to set
     */
    public void setAtmos(AtmosApi atmos) {
        this.atmos = atmos;
    }
}
