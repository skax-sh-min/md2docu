package com.md2docu.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class ResourceUri {
    private ResourceUri() {}

    public static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static boolean isAllowedLink(URI uri) {
        if (uri == null) return false;
        if (uri.getScheme() == null) {
            return uri.getRawAuthority() == null
                && ((uri.getPath() != null && !uri.getPath().isEmpty()) || uri.getFragment() != null);
        }
        return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http", "https", "ftp" -> hasAllowedHost(uri);
            case "mailto" -> !uri.getRawSchemeSpecificPart().isBlank();
            default -> false;
        };
    }

    public static boolean hasAllowedHost(URI uri) {
        return uri.getHost() != null && uri.getUserInfo() == null && uri.getPort() <= 65535;
    }
}
