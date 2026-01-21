package com.socotra.deployment.customer;

import com.socotra.coremodel.RatingItem;
import com.socotra.coremodel.RatingSet;
import com.socotra.deployment.customer.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ItemCareRatingPlugin implements RatePlugin {

    private static final Logger log = LoggerFactory.getLogger(ItemCareRatingPlugin.class);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DEFAULT_DURATION = BigDecimal.valueOf(12);
    private static final int DECIMAL_SCALE = 8;
    private static final int CURRENCY_SCALE = 2;
    private static final String DISCOUNT_TYPE_PERCENTAGE = "P";
    private static final String PRODUCT_TERM_SUBSCRIPTION = "Subscription";

    @Override
    public RatingSet rate(ItemCareQuoteRequest request) {
        log.info("Rating QUOTE request");
        ItemCareQuote quote = request.quote();
        BigDecimal discountAmount = getDiscountAmount(quote.data().discountAmount());
        String discountType = quote.data().discountType();
        String productTerm = quote.data().productTerm();
        Integer periodOfCover = quote.data().periodOfCover();
        BigDecimal duration = periodOfCover != null ? BigDecimal.valueOf(periodOfCover) : DEFAULT_DURATION;

        log.info("Quote rating - discountAmount: {}, discountType: {}, productTerm: {}, periodOfCover: {}, duration: {}",
                discountAmount, discountType, productTerm, periodOfCover, duration);
        log.info("Number of items to rate: {}", quote.items().size());

        List<RatingItem> ratingItems = rateItems(quote.items(), discountAmount, discountType, duration, productTerm);

        log.info("Quote rating complete - total rating items: {}", ratingItems.size());
        return RatingSet.builder()
                .ok(true)
                .ratingItems(ratingItems)
                .build();
    }

    @Override
    public RatingSet rate(ItemCareRequest request) {
        log.info("Rating SEGMENT request");
        List<RatingItem> ratingItems = new ArrayList<>();

        if (request.segment().isPresent()) {
            ItemCareSegment segment = request.segment().get();
            log.info("Segment locator: {}", segment.locator());
            BigDecimal discountAmount = getDiscountAmount(segment.data().discountAmount());
            String discountType = segment.data().discountType();
            String productTerm = segment.data().productTerm();
            Integer periodOfCover = segment.data().periodOfCover();
            BigDecimal duration = periodOfCover != null ? BigDecimal.valueOf(periodOfCover) : DEFAULT_DURATION;

            log.info("Segment rating - discountAmount: {}, discountType: {}, productTerm: {}, periodOfCover: {}, duration: {}",
                    discountAmount, discountType, productTerm, periodOfCover, duration);
            log.info("Number of items to rate: {}", segment.items().size());

            ratingItems = rateItems(segment.items(), discountAmount, discountType, duration, productTerm);
        } else {
            log.info("No segment present in request");
        }

        log.info("Segment rating complete - total rating items: {}", ratingItems.size());
        return RatingSet.builder()
                .ok(true)
                .ratingItems(ratingItems)
                .build();
    }

    private List<RatingItem> rateItems(Collection<? extends Item> items, BigDecimal discountAmount, String discountType,
            BigDecimal duration, String productTerm) {
        List<RatingItem> ratingItems = new ArrayList<>();

        // Determine tax scale based on product term: Subscription = 2 (currency), Fixed Term = 8 (precision)
        boolean isSubscription = PRODUCT_TERM_SUBSCRIPTION.equals(productTerm);
        int taxScale = isSubscription ? CURRENCY_SCALE : DECIMAL_SCALE;
        log.info("Product term: {}, isSubscription: {}, taxScale: {}", productTerm, isSubscription, taxScale);

        int itemIndex = 0;
        for (Item item : items) {
            log.info("Processing item {} - locator: {}", itemIndex, item.locator());

            if (item.accidentalDamage() != null) {
                AccidentalDamage coverage = item.accidentalDamage();
                log.info("  AccidentalDamage coverage locator: {}", coverage.locator());

                BigDecimal totalPremium = coverage.data().premium();
                log.info("  Total premium from data: {}", totalPremium);

                BigDecimal monthlyPremiumExclDiscount = totalPremium
                        .divide(duration, CURRENCY_SCALE, RoundingMode.HALF_UP);
                log.info("  Monthly premium excl discount: {} (totalPremium {} / duration {})",
                        monthlyPremiumExclDiscount, totalPremium, duration);

                BigDecimal monthlyPremium = applyDiscount(monthlyPremiumExclDiscount, discountAmount, discountType);
                log.info("  Monthly premium after discount: {} (discountAmount: {}, discountType: {})",
                        monthlyPremium, discountAmount, discountType);

                ratingItems.add(RatingItem.builder()
                        .elementLocator(coverage.locator())
                        .chargeType(ChargeType.premium)
                        .rate(monthlyPremium)
                        .build());
                log.info("  Added RatingItem: chargeType=premium, rate={}", monthlyPremium);

                ratingItems.add(RatingItem.builder()
                        .elementLocator(coverage.locator())
                        .chargeType(ChargeType.premiumExclDiscount)
                        .rate(monthlyPremiumExclDiscount)
                        .build());
                log.info("  Added RatingItem: chargeType=premiumExclDiscount, rate={}", monthlyPremiumExclDiscount);

                BigDecimal taxRate = coverage.data().taxRate();
                log.info("  Tax rate from data: {}", taxRate);
                if (taxRate != null && taxRate.compareTo(BigDecimal.ZERO) > 0) {
                    // taxRate is already a decimal (e.g., 0.05 for 5%)
                    BigDecimal taxAmount = monthlyPremium.multiply(taxRate)
                            .setScale(taxScale, RoundingMode.HALF_UP);
                    log.info("  Tax amount: {} (monthlyPremium {} * taxRate {}, taxScale {})",
                            taxAmount, monthlyPremium, taxRate, taxScale);

                    ratingItems.add(RatingItem.builder()
                            .elementLocator(coverage.locator())
                            .chargeType(ChargeType.tax)
                            .rate(taxAmount)
                            .build());
                    log.info("  Added RatingItem: chargeType=tax, rate={}", taxAmount);
                }
            } else {
                log.info("  No accidentalDamage coverage on this item");
            }

            itemIndex++;
        }

        return ratingItems;
    }

    private static BigDecimal getDiscountAmount(BigDecimal discountAmount) {
        return discountAmount != null ? discountAmount : BigDecimal.ZERO;
    }

    private BigDecimal applyDiscount(BigDecimal monthlyPremium, BigDecimal discountAmount, String discountType) {
        log.info("    applyDiscount - input: monthlyPremium={}, discountAmount={}, discountType={}",
                monthlyPremium, discountAmount, discountType);

        if (discountAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.info("    applyDiscount - no discount applied (discountAmount is zero)");
            return monthlyPremium;
        }

        if (DISCOUNT_TYPE_PERCENTAGE.equals(discountType)) {
            BigDecimal discountRate = discountAmount.divide(HUNDRED, DECIMAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal discount = monthlyPremium.multiply(discountRate);
            BigDecimal result = monthlyPremium.subtract(discount).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
            log.info("    applyDiscount - percentage: discountRate={}, discount={}, result={}",
                    discountRate, discount, result);
            return result;
        } else {
            BigDecimal result = monthlyPremium.subtract(discountAmount).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
            log.info("    applyDiscount - flat amount: result={}", result);
            return result;
        }
    }
}
