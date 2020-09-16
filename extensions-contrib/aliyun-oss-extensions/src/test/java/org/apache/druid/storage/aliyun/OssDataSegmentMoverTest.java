/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.storage.aliyun;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aliyun.oss.model.CopyObjectResult;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.PutObjectResult;
import com.aliyun.oss.model.StorageClass;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.MapUtils;
import org.apache.druid.segment.loading.SegmentLoadingException;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.NoneShardSpec;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class OssDataSegmentMoverTest
{
  private static final DataSegment SOURCE_SEGMENT = new DataSegment(
      "test",
      Intervals.of("2013-01-01/2013-01-02"),
      "1",
      ImmutableMap.of(
          "key",
          "baseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip",
          "bucket",
          "main"
      ),
      ImmutableList.of("dim1", "dim1"),
      ImmutableList.of("metric1", "metric2"),
      NoneShardSpec.instance(),
      0,
      1
  );

  @Test
  public void testMove() throws Exception
  {
    MockClient mockClient = new MockClient();
    OssDataSegmentMover mover = new OssDataSegmentMover(mockClient, new OssStorageConfig());

    mockClient.putObject(
        "main",
        "baseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip"
    );

    DataSegment movedSegment = mover.move(
        SOURCE_SEGMENT,
        ImmutableMap.of("baseKey", "targetBaseKey", "bucket", "archive")
    );

    Map<String, Object> targetLoadSpec = movedSegment.getLoadSpec();
    Assert.assertEquals(
        "targetBaseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip",
        MapUtils.getString(targetLoadSpec, "key")
    );
    Assert.assertEquals("archive", MapUtils.getString(targetLoadSpec, "bucket"));
    Assert.assertTrue(mockClient.didMove());
  }

  @Test
  public void testMoveNoop() throws Exception
  {
    MockClient mockOssClient = new MockClient();
    OssDataSegmentMover mover = new OssDataSegmentMover(mockOssClient, new OssStorageConfig());

    mockOssClient.putObject(
        "archive",
        "targetBaseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip"
    );

    DataSegment movedSegment = mover.move(
        SOURCE_SEGMENT,
        ImmutableMap.of("baseKey", "targetBaseKey", "bucket", "archive")
    );

    Map<String, Object> targetLoadSpec = movedSegment.getLoadSpec();

    Assert.assertEquals(
        "targetBaseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip",
        MapUtils.getString(targetLoadSpec, "key")
    );
    Assert.assertEquals("archive", MapUtils.getString(targetLoadSpec, "bucket"));
    Assert.assertFalse(mockOssClient.didMove());
  }

  @Test(expected = SegmentLoadingException.class)
  public void testMoveException() throws Exception
  {
    MockClient mockClient = new MockClient();
    OssDataSegmentMover mover = new OssDataSegmentMover(mockClient, new OssStorageConfig());

    mover.move(
        SOURCE_SEGMENT,
        ImmutableMap.of("baseKey", "targetBaseKey", "bucket", "archive")
    );
  }

  @Test
  public void testIgnoresGoneButAlreadyMoved() throws Exception
  {
    MockClient mockOssClient = new MockClient();
    OssDataSegmentMover mover = new OssDataSegmentMover(mockOssClient, new OssStorageConfig());
    mover.move(new DataSegment(
        "test",
        Intervals.of("2013-01-01/2013-01-02"),
        "1",
        ImmutableMap.of(
            "key",
            "baseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip",
            "bucket",
            "DOES NOT EXIST"
        ),
        ImmutableList.of("dim1", "dim1"),
        ImmutableList.of("metric1", "metric2"),
        NoneShardSpec.instance(),
        0,
        1
    ), ImmutableMap.of("bucket", "DOES NOT EXIST", "baseKey", "baseKey"));
  }

  @Test(expected = SegmentLoadingException.class)
  public void testFailsToMoveMissing() throws Exception
  {
    MockClient client = new MockClient();
    OssDataSegmentMover mover = new OssDataSegmentMover(client, new OssStorageConfig());
    mover.move(new DataSegment(
        "test",
        Intervals.of("2013-01-01/2013-01-02"),
        "1",
        ImmutableMap.of(
            "key",
            "baseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip",
            "bucket",
            "DOES NOT EXIST"
        ),
        ImmutableList.of("dim1", "dim1"),
        ImmutableList.of("metric1", "metric2"),
        NoneShardSpec.instance(),
        0,
        1
    ), ImmutableMap.of("bucket", "DOES NOT EXIST", "baseKey", "baseKey2"));
  }

  private static class MockClient extends OSSClient
  {
    Map<String, Set<String>> storage = new HashMap<>();
    boolean copied = false;
    boolean deletedOld = false;

    private MockClient()
    {
      super("endpoint", "accessKeyId", "keySecret");
    }

    public boolean didMove()
    {
      return copied && deletedOld;
    }

    @Override
    public boolean doesObjectExist(String bucketName, String objectKey)
    {
      Set<String> objects = storage.get(bucketName);
      return (objects != null && objects.contains(objectKey));
    }

    @Override
    public ObjectListing listObjects(ListObjectsRequest listObjectsV2Request)
    {
      final String bucketName = listObjectsV2Request.getBucketName();
      final String objectKey = listObjectsV2Request.getPrefix();
      if (doesObjectExist(bucketName, objectKey)) {
        final OSSObjectSummary objectSummary = new OSSObjectSummary();
        objectSummary.setBucketName(bucketName);
        objectSummary.setKey(objectKey);
        objectSummary.setStorageClass(StorageClass.Standard.name());

        final ObjectListing result = new ObjectListing();
        result.setBucketName(bucketName);
        result.setPrefix(objectKey);
        //result.setKeyCount(1);
        result.getObjectSummaries().add(objectSummary);
        result.setTruncated(true);
        return result;
      } else {
        return new ObjectListing();
      }
    }

    @Override
    public CopyObjectResult copyObject(CopyObjectRequest copyObjectRequest)
    {
      final String sourceBucketName = copyObjectRequest.getSourceBucketName();
      final String sourceObjectKey = copyObjectRequest.getSourceKey();
      final String destinationBucketName = copyObjectRequest.getDestinationBucketName();
      final String destinationObjectKey = copyObjectRequest.getDestinationKey();
      copied = true;
      if (doesObjectExist(sourceBucketName, sourceObjectKey)) {
        storage.computeIfAbsent(destinationBucketName, k -> new HashSet<>())
               .add(destinationObjectKey);
        return new CopyObjectResult();
      } else {
        final OSSException exception = new OSSException(
            "OssDataSegmentMoverTest",
            "NoSuchKey",
            null,
            null,
            null,
            null,
            null
        );
        throw exception;
      }
    }

    @Override
    public void deleteObject(String bucket, String objectKey)
    {
      deletedOld = true;
      storage.get(bucket).remove(objectKey);
    }

    public PutObjectResult putObject(String bucketName, String key)
    {
      return putObject(bucketName, key, (File) null);
    }

    @Override
    public PutObjectResult putObject(String bucketName, String key, File file)
    {
      storage.computeIfAbsent(bucketName, bName -> new HashSet<>()).add(key);
      return new PutObjectResult();
    }
  }
}