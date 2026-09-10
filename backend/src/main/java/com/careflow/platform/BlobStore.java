package com.careflow.platform;

import io.minio.*;
import java.io.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BlobStore {
  private final String endpoint, access, secret, bucket;

  public BlobStore(
      @Value("${careflow.s3-endpoint}") String endpoint,
      @Value("${careflow.s3-access-key}") String access,
      @Value("${careflow.s3-secret-key}") String secret,
      @Value("${careflow.s3-bucket}") String bucket) {
    this.endpoint = endpoint;
    this.access = access;
    this.secret = secret;
    this.bucket = bucket;
  }

  private MinioClient client() {
    if (access.isBlank() || secret.isBlank())
      throw new ApiException(503, "STORAGE_NOT_CONFIGURED", "对象存储尚未配置");
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(access, secret)
        .httpClient(
            new okhttp3.OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofSeconds(60))
                .writeTimeout(java.time.Duration.ofSeconds(60))
                .callTimeout(java.time.Duration.ofSeconds(120))
                .build())
        .build();
  }

  public void put(String key, byte[] bytes) {
    try {
      var c = client();
      if (!c.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
        c.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      c.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(
                  new ByteArrayInputStream(bytes), bytes.length, -1)
              .contentType("application/octet-stream")
              .build());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE", "上传存储失败");
    }
  }

  public void delete(String key) {
    try {
      client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE", "暂存文件清理失败");
    }
  }

  /** Remove all S3 versions and delete markers, then verify absence of this exact key. */
  public void purge(String key) {
    if (key.isBlank()) return;
    try {
      var c = client();
      if (!c.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) return;
      for (var result :
          c.listObjects(
              ListObjectsArgs.builder()
                  .bucket(bucket)
                  .prefix(key)
                  .recursive(true)
                  .includeVersions(true)
                  .build())) {
        var item = result.get();
        if (key.equals(item.objectName()))
          c.removeObject(
              RemoveObjectArgs.builder()
                  .bucket(bucket)
                  .object(key)
                  .versionId(item.versionId())
                  .build());
      }
      for (var result :
          c.listObjects(
              ListObjectsArgs.builder()
                  .bucket(bucket)
                  .prefix(key)
                  .recursive(true)
                  .includeVersions(true)
                  .build()))
        if (key.equals(result.get().objectName()))
          throw new IllegalStateException("Object versions remain");
    } catch (Exception e) {
      throw new ApiException(503, "STORAGE_PURGE_UNAVAILABLE", "原文件及历史副本清理尚未完成");
    }
  }

  public byte[] get(String key) {
    try (var stream =
        client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
      return stream.readAllBytes();
    } catch (Exception e) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE", "文件读取失败");
    }
  }
}
