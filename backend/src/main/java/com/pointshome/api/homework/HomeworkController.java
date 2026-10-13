package com.pointshome.api.homework;

import com.pointshome.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/homework")
public class HomeworkController {
    public record Question(@NotBlank @Size(max = 500) String question) {}
    public record ReviewRequest(@NotBlank @Pattern(regexp = "GOOD|AVERAGE|POOR") String rating,
                                @Size(max = 500) String note, String pointItemId) {}

    private final HomeworkService homework;

    public HomeworkController(HomeworkService homework) {
        this.homework = homework;
    }

    @PostMapping(value = "/children/{childId}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<HomeworkService.Detail> submit(@AuthenticationPrincipal CurrentUser user,
            @PathVariable String childId, @RequestParam String subject,
            @RequestParam(required = false) String instructions,
            @RequestPart("photos") List<MultipartFile> photos) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(homework.submit(user, childId, subject, instructions, photos));
    }

    @GetMapping("/children/{childId}/submissions")
    public List<HomeworkService.Summary> list(@AuthenticationPrincipal CurrentUser user,
                                               @PathVariable String childId) {
        return homework.list(user, childId);
    }

    @GetMapping("/submissions/{id}")
    public HomeworkService.Detail detail(@AuthenticationPrincipal CurrentUser user,
                                          @PathVariable String id) {
        return homework.detail(user, id);
    }

    @GetMapping("/submissions/{id}/photos/{ordinal}")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal CurrentUser user,
                                         @PathVariable String id, @PathVariable int ordinal) {
        HomeworkImageStore.Image image = homework.photo(user, id, ordinal);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mediaType())).body(image.bytes());
    }

    @PostMapping("/submissions/{id}/analyze")
    public HomeworkService.Detail analyze(@AuthenticationPrincipal CurrentUser user,
                                           @PathVariable String id) {
        return homework.analyze(user, id);
    }

    @PostMapping("/submissions/{id}/chat")
    public HomeworkService.Detail chat(@AuthenticationPrincipal CurrentUser user,
                                        @PathVariable String id,
                                        @Valid @RequestBody Question body) {
        return homework.followUp(user, id, body.question());
    }

    @PostMapping("/submissions/{id}/review")
    public HomeworkService.Detail review(@AuthenticationPrincipal CurrentUser user,
                                          @PathVariable String id,
                                          @Valid @RequestBody ReviewRequest body) {
        return homework.review(user, id, body.rating(), body.note(), body.pointItemId());
    }

    @GetMapping("/children/{childId}/rule")
    public HomeworkService.Rule rule(@AuthenticationPrincipal CurrentUser user,
                                     @PathVariable String childId) {
        return homework.rule(user, childId);
    }

    @PutMapping("/children/{childId}/rule")
    public HomeworkService.Rule saveRule(@AuthenticationPrincipal CurrentUser user,
                                         @PathVariable String childId,
                                         @RequestBody HomeworkService.Rule body) {
        return homework.saveRule(user, childId, body);
    }

    @DeleteMapping("/submissions/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentUser user,
                                        @PathVariable String id) {
        homework.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
