package org.example.bookmarket.usedbook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.bookmarket.ai.dto.PriceSuggestResponse;
import org.example.bookmarket.ai.service.AiService;
import org.example.bookmarket.book.entity.Book;
import org.example.bookmarket.book.repository.BookRepository;
import org.example.bookmarket.book.service.BookService;
import org.example.bookmarket.category.entity.Category;
import org.example.bookmarket.category.repository.CategoryRepository;
import org.example.bookmarket.common.handler.exception.CustomException;
import org.example.bookmarket.common.handler.exception.ErrorCode;
import org.example.bookmarket.common.service.S3UploadResponse;
import org.example.bookmarket.common.service.S3UploadService;
import org.example.bookmarket.usedbook.dto.UsedBookPostRequest;
import org.example.bookmarket.usedbook.entity.UsedBook;
import org.example.bookmarket.usedbook.entity.UsedBookImage;
import org.example.bookmarket.usedbook.repository.UsedBookRepository;
import org.example.bookmarket.user.entity.SocialType;
import org.example.bookmarket.user.entity.User;
import org.example.bookmarket.user.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsedBookPostService {

    private final UsedBookRepository usedBookRepository;
    private final BookService bookService;
    private final BookRepository bookRepository;
    private final AiService aiService;
    private final S3UploadService s3UploadService;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional
    public void registerUsedBook(UsedBookPostRequest request) {
        User seller = getCurrentUser();

        Book book = bookService.getOrCreateByIsbn(request.getIsbn());

        if (request.getNewPrice() != null) {
            if (book.getNewPrice() == null || !book.getNewPrice().equals(request.getNewPrice())) {
                book.setNewPrice(request.getNewPrice());
                bookRepository.save(book);
            }
        }

        List<S3UploadResponse> s3UploadResponses = new ArrayList<>();
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            for (MultipartFile imageFile : request.getImages()) {
                if (imageFile != null && !imageFile.isEmpty()) {
                    s3UploadResponses.add(s3UploadService.upload(imageFile, "used-book-images"));
                }
            }
        }

        if (s3UploadResponses.isEmpty()) {
            throw new CustomException(ErrorCode.USED_BOOK_IMAGE_REQUIRED);
        }

        PriceSuggestResponse aiResponse = null;
        String representativeImageKey = s3UploadResponses.get(0).key(); // AI 분석에는 Key 사용
        try {
            int basePrice = Objects.requireNonNullElse(book.getNewPrice(), 30000);
            aiResponse = aiService.suggestPriceFromImage(representativeImageKey, basePrice);
        } catch (IOException e) {
            log.error("책 등록 중 AI 이미지 분석에 실패했습니다. ISBN: {}", request.getIsbn(), e);
            throw new CustomException(ErrorCode.AI_ANALYSIS_FAILED);
        }

        if (request.getCategoryId() == null) {
            throw new CustomException(ErrorCode.INVALID_REQUEST, "카테고리를 선택해주세요.");
        }
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new CustomException(ErrorCode.CATEGORY_NOT_FOUND));

        UsedBook usedBook = UsedBook.builder()
                .seller(seller)
                .book(book)
                .category(category)
                .conditionGrade(request.getConditionGrade())
                .detailedCondition(request.getDetailedCondition())
                .sellingPrice(request.getSellingPrice())
                .aiSuggestedMinPrice(aiResponse != null ? aiResponse.getSuggestedMinPrice() : null)
                .aiSuggestedMaxPrice(aiResponse != null ? aiResponse.getSuggestedMaxPrice() : null)
                .aiDetectedDefects(aiResponse != null ? aiResponse.getDetectedDefects() : null)
                .status("FOR_SALE")
                .build();

        List<String> imageUrls = s3UploadResponses.stream().map(S3UploadResponse::url).toList();
        List<UsedBookImage> usedBookImages = imageUrls.stream()
                .map(url -> UsedBookImage.builder().imageUrl(url).build())
                .toList();
        usedBook.setImages(usedBookImages);

        usedBookRepository.save(usedBook);
        log.info("새로운 중고책이 등록되었습니다. ID: {}", usedBook.getId());
    }

    @Transactional
    public void deleteUsedBook(Long usedBookId) {
        User currentUser = getCurrentUser();
        UsedBook usedBook = usedBookRepository.findById(usedBookId)
                .orElseThrow(() -> new CustomException(ErrorCode.USED_BOOK_NOT_FOUND));
        if (!usedBook.getSeller().getId().equals(currentUser.getId())) {
            throw new CustomException(ErrorCode.USED_BOOK_DELETE_FORBIDDEN);
        }
        if ("판매 완료".equalsIgnoreCase(usedBook.getStatus()) || "SOLD".equalsIgnoreCase(usedBook.getStatus())) {
            throw new CustomException(ErrorCode.BOOK_ALREADY_SOLD);
        }
        usedBookRepository.delete(usedBook);
        log.info("중고책이 삭제되었습니다. ID: {}", usedBookId);
    }

    @Transactional
    public void updateUsedBook(Long usedBookId, UsedBookPostRequest request) {
        User currentUser = getCurrentUser();
        UsedBook usedBook = usedBookRepository.findById(usedBookId)
                .orElseThrow(() -> new CustomException(ErrorCode.USED_BOOK_NOT_FOUND));
        if (!usedBook.getSeller().getId().equals(currentUser.getId())) {
            throw new CustomException(ErrorCode.USED_BOOK_DELETE_FORBIDDEN);
        }
        if ("판매 완료".equalsIgnoreCase(usedBook.getStatus()) || "SOLD".equalsIgnoreCase(usedBook.getStatus())) {
            throw new CustomException(ErrorCode.BOOK_ALREADY_SOLD);
        }
        if (request.getSellingPrice() != null) {
            usedBook.setSellingPrice(request.getSellingPrice());
        }
        if (request.getDetailedCondition() != null) {
            usedBook.setDetailedCondition(request.getDetailedCondition());
        }
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new CustomException(ErrorCode.CATEGORY_NOT_FOUND));
            usedBook.setCategory(category);
        }
        usedBookRepository.save(usedBook);
        log.info("중고책이 수정되었습니다. ID: {}", usedBookId);
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new CustomException(ErrorCode.LOGIN_REQUIRED);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user;
        } else if (principal instanceof OAuth2User oauth2User) {
            String socialId = oauth2User.getAttribute("id").toString();
            return userRepository.findBySocialTypeAndSocialId(SocialType.KAKAO, socialId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND, "소셜 로그인 사용자를 DB에서 찾을 수 없습니다."));
        }

        throw new CustomException(ErrorCode.AUTHENTICATION_FAILED, "지원하지 않는 인증 방식입니다.");
    }
}