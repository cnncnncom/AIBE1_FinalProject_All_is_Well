package org.example.bookmarket.common.service;

import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import org.example.bookmarket.common.handler.exception.CustomException;
import org.example.bookmarket.common.handler.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3UploadService {

    private final S3Template s3Template;

    @Value("${spring.cloud.aws.s3.bucket.name}")
    private String bucketName;


    public S3UploadResponse upload(MultipartFile multipartFile, String dirName) {
        try {
            String originalFilename = multipartFile.getOriginalFilename();
            String uniqueFilename = dirName + "/" + UUID.randomUUID() + "-" + originalFilename;

            String url = s3Template
                    .upload(bucketName, uniqueFilename, multipartFile.getInputStream())
                    .getURL()
                    .toString();


            return new S3UploadResponse(url, uniqueFilename);

        } catch (IOException e) {
            throw new CustomException(ErrorCode.S3_UPLOAD_FAILED);
        }
    }
}