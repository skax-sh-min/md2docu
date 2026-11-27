package com.md2docu.util;

import com.md2docu.model.UserSettings;
import java.util.Locale;

public final class PageLayout {
    public static final int MIN_BODY_WIDTH_MM = 180;
    public static final int MIN_BODY_HEIGHT_MM = 250;

    private PageLayout() {}

    public static void validateAll(UserSettings settings) {
        validate("A4", settings);
        validate("LETTER", settings);
    }

    public static void validate(String pageSize, UserSettings settings) {
        double width;
        double height;
        if ("A4".equalsIgnoreCase(pageSize)) {
            width = 210;
            height = 297;
        } else if ("LETTER".equalsIgnoreCase(pageSize)) {
            width = 215.9;
            height = 279.4;
        } else {
            throw new InvalidLayoutException("지원하지 않는 페이지 크기입니다: " + pageSize);
        }
        double bodyWidth = width - settings.getMarginLeftMm() - settings.getMarginRightMm();
        double bodyHeight = height - settings.getMarginTopMm() - settings.getMarginBottomMm();
        if (bodyWidth < MIN_BODY_WIDTH_MM || bodyHeight < MIN_BODY_HEIGHT_MM) {
            throw new InvalidLayoutException(String.format(Locale.ROOT,
                "%s의 본문 영역 %.1f×%.1fmm는 최소 %d×%dmm보다 작습니다. 여백을 줄이거나 출력 설정을 기본값으로 복원한 뒤 저장하세요.",
                pageSize, bodyWidth, bodyHeight, MIN_BODY_WIDTH_MM, MIN_BODY_HEIGHT_MM));
        }
    }

    public static final class InvalidLayoutException extends IllegalArgumentException {
        private InvalidLayoutException(String message) { super(message); }
    }
}
