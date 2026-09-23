package com.pragmaticds.docengine.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The empty-upload guard runs before any collaborator is touched, so it is provable without a
 * container: a service built on nulls must still reject an empty request cleanly.
 */
class UploadServiceTest {

    private final UploadService service =
            new UploadService(
                    null, null, null, null, null, null, null, new MimeSnifferStandIn(), null, 100,
                    10);

    /** Never reached — the guard fires first. */
    private static final class MimeSnifferStandIn
            extends com.pragmaticds.docengine.ingestion.validate.MimeSniffer {}

    @Test
    void an_empty_file_list_is_an_empty_upload() {
        DomainException exception =
                catchThrowableOfType(
                        DomainException.class, () -> service.upload(List.of(), null, null, null, false));

        assertThat(exception.code()).isEqualTo(ErrorCode.EMPTY_UPLOAD);
        assertThat(exception.httpStatus()).isEqualTo(400);
    }

    @Test
    void a_null_file_list_is_an_empty_upload() {
        DomainException exception =
                catchThrowableOfType(
                        DomainException.class, () -> service.upload(null, null, null, null, false));

        assertThat(exception.code()).isEqualTo(ErrorCode.EMPTY_UPLOAD);
    }

    @Test
    void all_zero_byte_files_are_an_empty_upload() {
        List<FileUpload> uploads =
                List.of(
                        new FileUpload("a.pdf", "application/pdf", new byte[0]),
                        new FileUpload("b.pdf", "application/pdf", new byte[0]));

        DomainException exception =
                catchThrowableOfType(
                        DomainException.class, () -> service.upload(uploads, null, null, null, false));

        assertThat(exception.code()).isEqualTo(ErrorCode.EMPTY_UPLOAD);
    }
}
