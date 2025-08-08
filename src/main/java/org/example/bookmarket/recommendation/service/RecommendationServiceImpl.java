package org.example.bookmarket.recommendation.service;

import lombok.RequiredArgsConstructor;
import org.example.bookmarket.trade.repository.TradeRepository;
import org.example.bookmarket.usedbook.dto.UsedBookResponse;
import org.example.bookmarket.usedbook.entity.UsedBook;
import org.example.bookmarket.usedbook.repository.UsedBookRepository;
import org.example.bookmarket.usedbook.service.UsedBookQueryService;
import org.example.bookmarket.user.entity.User;
import org.example.bookmarket.user.repository.UserCategoryRepository;
import org.example.bookmarket.user.repository.UserRepository;
import org.example.bookmarket.wishlist.entity.Wishlist;
import org.example.bookmarket.wishlist.repository.WishlistRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private final UserRepository userRepository;
    private final WishlistRepository wishlistRepository;
    private final UserCategoryRepository userCategoryRepository;
    private final UsedBookRepository usedBookRepository;
    private final TradeRepository tradeRepository;
    private final UsedBookQueryService usedBookQueryService;

    @Override
    @Transactional(readOnly = true)
    public List<UsedBookResponse> getPersonalizedRecommendations(Long userId, int limit) {
        if (userId == null) {
            return usedBookQueryService.getLatestUsedBooks(limit);
        }

        final Set<Long> excludedBookIds = new HashSet<>();
        List<Wishlist> userWishlist = wishlistRepository.findByUserId(userId);
        userWishlist.forEach(w -> {
            if (w.getUsedBook() != null && w.getUsedBook().getId() != null) {
                excludedBookIds.add(w.getUsedBook().getId());
            }
        });

        tradeRepository.findByBuyerId(userId).forEach(t -> {
            if (t.getUsedBook() != null && t.getUsedBook().getId() != null) {
                excludedBookIds.add(t.getUsedBook().getId());
            }
        });

        if (excludedBookIds.isEmpty()) {
            excludedBookIds.add(-1L);
        }

        final Set<Long> recommendationCategoryIds = new HashSet<>();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        userCategoryRepository.findByUser(user).forEach(uc -> {
            if (uc.getCategory() != null && uc.getCategory().getId() != null) {
                recommendationCategoryIds.add(uc.getCategory().getId());
            }
        });

        userWishlist.forEach(w -> {
            if (w.getUsedBook() != null && w.getUsedBook().getCategory() != null && w.getUsedBook().getCategory().getId() != null) {
                recommendationCategoryIds.add(w.getUsedBook().getCategory().getId());
            }
        });

        List<UsedBook> recommendedBooks = new ArrayList<>();

        if (!recommendationCategoryIds.isEmpty()) {
            recommendedBooks = usedBookRepository.findRecommendationsByCategory(
                    recommendationCategoryIds, excludedBookIds, userId, PageRequest.of(0, limit));
        }

        if (recommendedBooks.size() < limit) {
            int needed = limit - recommendedBooks.size();
            recommendedBooks.forEach(book -> excludedBookIds.add(book.getId()));
            List<UsedBook> generalRecommendations = usedBookRepository.findGeneralRecommendations(
                    excludedBookIds, userId, PageRequest.of(0, needed));
            recommendedBooks.addAll(generalRecommendations);
        }

        return recommendedBooks.stream().map(this::toResponse).collect(Collectors.toList());
    }


    private UsedBookResponse toResponse(UsedBook ub) {

        String coverImageUrl = (ub.getBook() != null && ub.getBook().getCoverImageUrl() != null)
                ? ub.getBook().getCoverImageUrl()
                : "/images/default-book.png";

        return new UsedBookResponse(
                ub.getId(),
                ub.getBook().getId(),
                ub.getBook().getIsbn(),
                ub.getBook().getTitle(),
                ub.getBook().getAuthor(),
                ub.getBook().getPublisher(),
                ub.getBook().getPublicationYear(),
                ub.getConditionGrade(),
                ub.getDetailedCondition(),
                ub.getSellingPrice(),
                ub.getStatus(),
                ub.getCategory().getId(),
                ub.getSeller().getId(),
                ub.getSeller().getNickname(),
                ub.getSeller().getProfileImageUrl(),
                coverImageUrl,
                null,
                null
        );
    }
}