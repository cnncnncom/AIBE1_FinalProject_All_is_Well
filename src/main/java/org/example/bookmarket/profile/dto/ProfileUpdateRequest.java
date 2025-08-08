package org.example.bookmarket.profile.dto;

import java.util.List;

public record ProfileUpdateRequest(
        String nickname,
        String profileImageUrl,
        List<Long> interestCategoryIds
) {}