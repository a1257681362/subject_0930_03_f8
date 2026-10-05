package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.PhotoFile;
import com.evops.mapper.PhotoFileMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 现场照片归档支持：复用影像归档的 SHA-256 内容寻址存储。
 * 虫害现场照片不套文物影像 800×800 归档下限，仅要求可解码；
 * 同一内容全库只一份物理文件，重复上传累加引用计数，不物理删除。
 */
@Component
public class PhotoFileStore {

    private final ImageStorage imageStorage;
    private final PhotoFileMapper photoFileMapper;

    public PhotoFileStore(ImageStorage imageStorage, PhotoFileMapper photoFileMapper) {
        this.imageStorage = imageStorage;
        this.photoFileMapper = photoFileMapper;
    }

    /**
     * 落物理文件并取得（或复用）内容寻址档案行。
     * 不可解码等错误以 IllegalArgumentException 透传给全局异常处理。
     */
    public PhotoFile store(byte[] content, String originalName, String contentType, String uploader) {
        // 现场照片不套文物影像 800×800 归档下限，仅要求可解码
        ImageStorage.StoredPhoto stored =
                imageStorage.store(content, originalName, contentType, false);
        PhotoFile existing = photoFileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                .eq(PhotoFile::getContentHash, stored.hash));
        if (existing != null) {
            existing.setRefCount(existing.getRefCount() + 1);
            photoFileMapper.updateById(existing);
            return existing;
        }
        PhotoFile photoFile = new PhotoFile();
        photoFile.setContentHash(stored.hash);
        photoFile.setStoragePath(stored.storagePath);
        photoFile.setFileName(stored.fileName);
        photoFile.setContentType(stored.contentType);
        photoFile.setFileSize(stored.size);
        photoFile.setWidthPx(stored.width);
        photoFile.setHeightPx(stored.height);
        photoFile.setShotAt(LocalDateTime.now());
        photoFile.setPhotographer(uploader);
        photoFile.setRefCount(1);
        try {
            photoFileMapper.insert(photoFile);
            return photoFile;
        } catch (DuplicateKeyException dup) {
            PhotoFile raced = photoFileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                    .eq(PhotoFile::getContentHash, stored.hash));
            raced.setRefCount(raced.getRefCount() + 1);
            photoFileMapper.updateById(raced);
            return raced;
        }
    }
}
