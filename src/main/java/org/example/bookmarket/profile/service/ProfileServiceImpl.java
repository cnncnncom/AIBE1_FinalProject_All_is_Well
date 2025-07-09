package org.example.bookmarket.profile.service;

import lombok.RequiredArgsConstructor;
import org.example.bookmarket.category.entity.Category;
import org.example.bookmarket.category.repository.CategoryRepository;
import org.example.bookmarket.chat.dto.ChatSummary;
import org.example.bookmarket.chat.entity.ChatChannel;
import org.example.bookmarket.chat.entity.ChatMessage;
import org.example.bookmarket.chat.repository.ChatChannelRepository;
import org.example.bookmarket.chat.repository.ChatMessageRepository;
import org.example.bookmarket.common.handler.exception.CustomException;
import org.example.bookmarket.common.handler.exception.ErrorCode;
import org.example.bookmarket.common.service.S3UploadService;
import org.example.bookmarket.profile.dto.ProfileResponse;
import org.example.bookmarket.profile.dto.ProfileUpdateRequest;
import org.example.bookmarket.trade.dto.PurchaseSummary;
import org.example.bookmarket.trade.entity.Trade;
import org.example.bookmarket.trade.repository.TradeRepository;
import org.example.bookmarket.usedbook.dto.UsedBookSummary;
import org.example.bookmarket.user.dto.UserCategoryResponse;
import org.example.bookmarket.user.entity.User;
import org.example.bookmarket.user.entity.UserCategory;
import org.example.bookmarket.user.repository.UserCategoryRepository;
import org.example.bookmarket.user.repository.UserRepository;
import org.example.bookmarket.usedbook.repository.UsedBookRepository;
import org.example.bookmarket.wishlist.dto.WishlistItem;
import org.example.bookmarket.wishlist.service.WishlistService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final UserCategoryRepository userCategoryRepository;
    private final UsedBookRepository usedBookRepository;
    private final TradeRepository tradeRepository;
    private final ChatChannelRepository chatChannelRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final S3UploadService s3UploadService;
    private final WishlistService wishlistService;

    @Override
    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        List<UserCategoryResponse> categories = userCategoryRepository.findByUser(user)
                .stream()
                .map(uc -> new UserCategoryResponse(
                        uc.getCategory().getId(),
                        uc.getCategory().getName()))
                .collect(Collectors.toList());

        return new ProfileResponse(
                user.getNickname(),
                user.getEmail(),
                user.getProfileImageUrl(),
                categories
        );
    }

    @Override
    @Transactional
    public void updateMyProfile(Long userId, ProfileUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (request.profileImageUrl() != null) {
            user.setProfileImageUrl(request.profileImageUrl());
        }

        if (request.interestCategoryIds() != null) {
            userCategoryRepository.deleteByUser(user);
            List<Category> categories = categoryRepository.findAllById(request.interestCategoryIds());
            List<UserCategory> userCategories = categories.stream()
                    .map(cat -> UserCategory.builder().user(user).category(cat).build())
                    .collect(Collectors.toList());
            userCategoryRepository.saveAll(userCategories);
        }
    }

    @Override
    @Transactional
    public String uploadProfileImage(Long userId, MultipartFile image) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (image == null || image.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_PROFILE_IMAGE);
        }
        String url = s3UploadService.upload(image, "profile-images");
        user.setProfileImageUrl(url);
        return url;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSummary> getMyDmList(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return chatChannelRepository.findByUser1OrUser2(user, user).stream()
                .map(ch -> {
                    ChatMessage lastMsg = chatMessageRepository
                            .findFirstByChannelOrderBySentAtDesc(ch)
                            .orElse(null);
                    User partner = ch.getUser1().getId().equals(userId) ? ch.getUser2() : ch.getUser1();
                    String lastContent = (lastMsg != null) ? lastMsg.getMessageContent() : "대화를 시작해보세요.";
                    return new ChatSummary(ch.getId(), lastContent, partner.getNickname(), ch.getLastMessageAt());
                })
                .collect(Collectors.toList());
    }

    /**
     * 판매 완료된 책의 경우, 거래 내역에서 구매자 닉네임을 찾아 함께 반환합니다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<UsedBookSummary> getMySellBooks(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        return usedBookRepository.findBySellerId(userId).stream()
                .map(ub -> {
                    String buyerNickname = null;
                    if ("판매 완료".equalsIgnoreCase(ub.getStatus()) || "SOLD".equalsIgnoreCase(ub.getStatus())) {
                        List<Trade> trades = tradeRepository.findBySellerId(userId);
                        for (Trade trade : trades) {
                            if (trade.getUsedBook().getId().equals(ub.getId())) {
                                buyerNickname = trade.getBuyer().getNickname();
                                break;
                            }
                        }
                    }
                    return new UsedBookSummary(
                            ub.getId(),               // 1. 중고책 ID
                            ub.getBook().getTitle(),  // 2. 책 제목
                            ub.getSellingPrice(),     // 3. 판매 가격
                            ub.getStatus(),           // 4. 판매 상태
                            buyerNickname,            // 5. 구매자 닉네임
                            ub.getUpdatedAt()         // 6. 최종 수정일
                    );
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseSummary> getMyPurchases(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        return tradeRepository.findByBuyerId(userId).stream()
                .map(tx -> new PurchaseSummary(
                        tx.getId(),
                        tx.getUsedBook().getBook().getTitle(),
                        tx.getSeller().getNickname(),
                        tx.getAgreedPrice(),
                        tx.getStatus().name(),
                        tx.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WishlistItem> getMyWishlist(Long userId) {
        return wishlistService.getItems(userId);
    }
}