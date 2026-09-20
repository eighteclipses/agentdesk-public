package com.agentdesk.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.UUID;

@Service
public class StorageService {
  private final MinioClient client;
  @Value("${minio.bucket:agentdesk}") private String bucket;
  public StorageService(MinioClient client) { this.client = client; }
  public String upload(long ticketId, MultipartFile file) {
    return uploadObject("tickets/" + ticketId, file);
  }
  public String uploadDocument(MultipartFile file) { return uploadObject("documents", file); }
  private String uploadObject(String prefix, MultipartFile file) {
    try {
      if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
       String key = prefix + "/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
      client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(file.getInputStream(), file.getSize(), -1).contentType(file.getContentType()).build());
      return key;
    } catch (Exception e) { throw new IllegalStateException("附件上传失败: " + e.getMessage(), e); }
  }
  /** 读取对象字节：导入流水线下载原件交给解析服务，以及文件详情页/附件下载。 */
  public byte[] downloadBytes(String objectKey) {
    try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
      return in.readAllBytes();
    } catch (Exception e) { throw new IllegalStateException("对象读取失败: " + e.getMessage(), e); }
  }
  public InputStream download(String objectKey) {
    try {
      return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    } catch (Exception e) { throw new IllegalStateException("对象读取失败: " + e.getMessage(), e); }
  }
  /** 删除对象：批次创建失败回滚时清理已上传的原件，避免孤儿数据。删除失败仅记录不抛出。 */
  public void deleteQuietly(String objectKey) {
    if (objectKey == null || objectKey.isBlank()) return;
    try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build()); }
    catch (Exception ignored) { /* 清理失败不影响主流程，宁可留孤儿也不掩盖原始错误 */ }
  }
}
