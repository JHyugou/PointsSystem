package com.pointshome.api.homework;

import com.pointshome.api.common.ApiProblem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class HomeworkImageStore {
    public record Photo(String mediaType, int byteSize) {}
    public record Image(byte[] bytes, String mediaType) {}

    private final Path root;

    public HomeworkImageStore(@Value("${app.homework.storage-dir:./data/homework}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public List<Photo> save(String submissionId, List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.size() > 3) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "HOMEWORK_PHOTO_COUNT", "请选择 1 到 3 张作业照片");
        }
        List<Image> images = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                if (file.isEmpty() || file.getSize() > 2_500_000) throw invalidImage();
                byte[] bytes = file.getBytes();
                images.add(validate(bytes));
            } catch (IOException ignored) {
                throw invalidImage();
            }
        }
        Path folder = folder(submissionId);
        try {
            Files.createDirectories(folder);
            List<Photo> result = new ArrayList<>();
            for (int index = 0; index < images.size(); index++) {
                Image image = images.get(index);
                Files.write(folder.resolve(fileName(index, image.mediaType())), image.bytes());
                result.add(new Photo(image.mediaType(), image.bytes().length));
            }
            return result;
        } catch (IOException ignored) {
            delete(submissionId);
            throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, "HOMEWORK_STORE_FAILED", "无法保存作业照片");
        }
    }

    public Image read(String submissionId, int ordinal, String mediaType) {
        if (ordinal < 0 || ordinal > 2) throw invalidImage();
        try {
            byte[] bytes = Files.readAllBytes(folder(submissionId).resolve(fileName(ordinal, mediaType)));
            return new Image(bytes, mediaType);
        } catch (IOException ignored) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "HOMEWORK_IMAGE_MISSING", "作业照片不存在");
        }
    }

    public void delete(String submissionId) {
        Path folder = folder(submissionId);
        for (int index = 0; index < 3; index++) {
            try { Files.deleteIfExists(folder.resolve(index + ".jpg")); } catch (IOException ignored) {}
            try { Files.deleteIfExists(folder.resolve(index + ".png")); } catch (IOException ignored) {}
        }
        try { Files.deleteIfExists(folder); } catch (IOException ignored) {}
    }

    private Image validate(byte[] bytes) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                String mediaType = switch (format.toLowerCase()) {
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "png" -> "image/png";
                    default -> throw invalidImage();
                };
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096
                        || width * height > 8_000_000) throw invalidImage();
                return new Image(bytes, mediaType);
            } finally {
                reader.dispose();
            }
        } catch (IOException ignored) {
            throw invalidImage();
        }
    }

    private Path folder(String submissionId) {
        return root.resolve(submissionId);
    }

    private String fileName(int ordinal, String mediaType) {
        return ordinal + ("image/png".equals(mediaType) ? ".png" : ".jpg");
    }

    private ApiProblem invalidImage() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "HOMEWORK_IMAGE_INVALID",
                "图片需为 JPEG 或 PNG，单张不超过 2.5 MB、最长边不超过 4096 像素");
    }
}
