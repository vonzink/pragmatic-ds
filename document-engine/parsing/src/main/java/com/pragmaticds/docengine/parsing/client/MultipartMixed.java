package com.pragmaticds.docengine.parsing.client;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Byte-exact parser for the {@code /v1/render} {@code multipart/mixed} response. Spring has no
 * client-side multipart reader, and the PNG parts are binary — so this walks the raw bytes: parts
 * are delimited by {@code --boundary} lines, each part is headers + blank line + content, and the
 * body ends with {@code --boundary--}.
 *
 * <p>Part naming: the {@code name} parameter of {@code Content-Disposition}, falling back to
 * {@code Content-ID}. The contract names the JSON part {@code metadata} and each PNG part by its
 * {@code pngPart} value.
 */
final class MultipartMixed {

    record Part(String contentType, byte[] content) {}

    private static final Pattern NAME_PARAM =
            Pattern.compile("name=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private MultipartMixed() {}

    static Map<String, Part> parse(byte[] body, String boundary) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] partEnd = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        Map<String, Part> parts = new LinkedHashMap<>();
        int cursor = indexOf(body, delimiter, 0);
        while (cursor >= 0) {
            cursor += delimiter.length;
            // Closing delimiter: --boundary--
            if (cursor + 1 < body.length && body[cursor] == '-' && body[cursor + 1] == '-') {
                break;
            }
            // Skip the CRLF after the boundary line.
            if (cursor + 1 < body.length && body[cursor] == '\r' && body[cursor + 1] == '\n') {
                cursor += 2;
            }
            int headersUntil = indexOf(body, headerEnd, cursor);
            if (headersUntil < 0) {
                break;
            }
            String headerBlock =
                    new String(body, cursor, headersUntil - cursor, StandardCharsets.ISO_8859_1);
            int contentStart = headersUntil + headerEnd.length;
            int contentUntil = indexOf(body, partEnd, contentStart);
            if (contentUntil < 0) {
                break;
            }
            byte[] content = Arrays.copyOfRange(body, contentStart, contentUntil);
            Map<String, String> headers = parseHeaders(headerBlock);
            String name = partName(headers);
            if (name != null) {
                parts.put(name, new Part(headers.get("content-type"), content));
            }
            cursor = indexOf(body, delimiter, contentUntil);
        }
        return parts;
    }

    private static Map<String, String> parseHeaders(String headerBlock) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : headerBlock.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static String partName(Map<String, String> headers) {
        String disposition = headers.get("content-disposition");
        if (disposition != null) {
            Matcher matcher = NAME_PARAM.matcher(disposition);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        String contentId = headers.get("content-id");
        if (contentId != null) {
            return contentId.replace("<", "").replace(">", "").trim();
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (from < 0) {
            return -1;
        }
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
