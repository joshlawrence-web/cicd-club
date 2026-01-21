package com.socotra.deployment.customer;

import com.socotra.deployment.plugins.PluginExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CancellationInvoicesAutomationPluginImpl
        implements CancellationInvoicesAutomationPlugin {

    private static final Logger log =
            LoggerFactory.getLogger(CancellationInvoicesAutomationPluginImpl.class);

    // TEMP: hard-coded for sandbox – replace with secret-based config later
    private static final String BASE_URL  = "https://api-ec-sandbox.socotra.com"; // no trailing slash
    private static final String API_TOKEN = "SOCP_01KEVM2GG0X461BFP699PKBWTN";     // your PAT

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Override
    public CancellationInvoicesLoadInvoiceAuxDataResponse loadInvoiceAuxData(
            CancellationInvoicesLoadInvoiceAuxDataRequest request) {

        PluginExecutionContext ctx = PluginExecutionContext.get();

        log.info("CancellationInvoices.loadInvoiceAuxData invoked");
        log.info("Request: {}", request);
        log.info("Context: requestId={}, tenant={}, businessAccount={}",
                ctx.getRequestId(), ctx.getTenantLocator(), ctx.getBusinessAccount());

        String policyLocator = request.policyLocator();
        String cancellationComments = request.cancellationComments();
        log.info("Target policyLocator = {}", policyLocator);

        try {
            // 1) Fetch all invoices for this policy (no filtering here)
            List<JsonNode> invoiceSummaries =
                    fetchPaidInvoicesForPolicy(ctx, BASE_URL, policyLocator, API_TOKEN); // helper now returns ALL

            log.info("Found {} invoices for policy {}", invoiceSummaries.size(), policyLocator);

// 2) For each invoice, figure out how much is paid and allocate premium/tax
            BigDecimal totalPremiumCollected = BigDecimal.ZERO;
            BigDecimal totalTaxCollected     = BigDecimal.ZERO;

            for (JsonNode invSummary : invoiceSummaries) {
                String invoiceLocator = invSummary.path("locator").asText(null);
                if (invoiceLocator == null || invoiceLocator.isBlank()) {
                    continue;
                }

                BigDecimal totalAmount =
                        invSummary.hasNonNull("totalAmount")
                                ? invSummary.get("totalAmount").decimalValue()
                                : BigDecimal.ZERO;

                BigDecimal totalRemaining =
                        invSummary.hasNonNull("totalRemainingAmount")
                                ? invSummary.get("totalRemainingAmount").decimalValue()
                                : BigDecimal.ZERO;

                BigDecimal paidAmount = totalAmount.subtract(totalRemaining);

                // Skip invoices with no payment at all
                if (paidAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    log.info("Skipping invoice {} because totalAmount <= 0 (totalAmount={}, paidAmount={})",
                            invoiceLocator, totalAmount, paidAmount);
                    continue;
                }

                BigDecimal paidFraction = paidAmount.divide(
                        totalAmount,
                        10,
                        java.math.RoundingMode.HALF_UP);

                log.info("Invoice {}: totalAmount={}, remaining={}, paidAmount={}, paidFraction={}",
                        invoiceLocator, totalAmount, totalRemaining, paidAmount, paidFraction);

                // Fetch invoice with items to know the premium/tax split
                JsonNode invoice =
                        fetchInvoiceWithItems(ctx, BASE_URL, invoiceLocator, API_TOKEN);

                JsonNode invoiceItems = invoice.path("invoiceItems");
                if (!invoiceItems.isArray()) {
                    log.info("Invoice {} has no invoiceItems array; skipping", invoiceLocator);
                    continue;
                }

                BigDecimal invoicePremiumTotal = BigDecimal.ZERO;
                BigDecimal invoiceTaxTotal     = BigDecimal.ZERO;

                for (JsonNode item : invoiceItems) {
                    String chargeCategory = item.path("chargeCategory").asText(null);
                    if (chargeCategory == null) {
                        continue;
                    }

                    JsonNode amountNode = item.get("amount");
                    if (amountNode == null || amountNode.isNull()) {
                        continue;
                    }

                    BigDecimal amount = amountNode.decimalValue();

                    if ("premium".equalsIgnoreCase(chargeCategory)
                            || "surcharge".equalsIgnoreCase(chargeCategory)) {
                        invoicePremiumTotal = invoicePremiumTotal.add(amount);
                    } else if ("tax".equalsIgnoreCase(chargeCategory)) {
                        invoiceTaxTotal = invoiceTaxTotal.add(amount);
                    }
                }

                if (invoicePremiumTotal.compareTo(BigDecimal.ZERO) == 0
                        && invoiceTaxTotal.compareTo(BigDecimal.ZERO) == 0) {
                    log.info("Invoice {} has no premium/tax items; skipping", invoiceLocator);
                    continue;
                }

                // Only the paid portion
                BigDecimal premiumPaidForInvoice =
                        invoicePremiumTotal.multiply(paidFraction)
                                .setScale(2, java.math.RoundingMode.HALF_UP);
                BigDecimal taxPaidForInvoice =
                        invoiceTaxTotal.multiply(paidFraction)
                                .setScale(2, java.math.RoundingMode.HALF_UP);

                log.info("Invoice {}: invoicePremiumTotal={}, invoiceTaxTotal={}, premiumPaid={}, taxPaid={}",
                        invoiceLocator, invoicePremiumTotal, invoiceTaxTotal,
                        premiumPaidForInvoice, taxPaidForInvoice);

                totalPremiumCollected = totalPremiumCollected.add(premiumPaidForInvoice);
                totalTaxCollected     = totalTaxCollected.add(taxPaidForInvoice);
            }

            log.info("Computed totals for policy {}: premium={}, tax={}",
                    policyLocator, totalPremiumCollected, totalTaxCollected);
            // 3) Write totals into Aux Data on the policy
            writePaidTotalsAuxData(
                    ctx,
                    BASE_URL,
                    policyLocator,
                    API_TOKEN,
                    totalPremiumCollected,
                    totalTaxCollected
            );

        } catch (Exception e) {
            log.error("Error loading invoice aux data for policy {}", policyLocator, e);
            // Optional: throw AutomationPluginException(500, "Error loading invoice aux data");
        }

        //write cancellation comments to aux data
        try {
            writeCancellationCommentsAuxData(
                    ctx,
                    BASE_URL,
                    policyLocator,
                    API_TOKEN,
                    cancellationComments
            );
        } catch (Exception e ){

            log.error("Error loading cancellation comments aux data for policy {}", policyLocator, e);
            // Optional: throw AutomationPluginException(500, "Error loading cancellation comments aux data");
        }

        //cancellation behaviour driven by input mode
        String mode = request.cancellationMode();
        String cancellationDate = "";
        if (mode == null) {
            mode = "";
        }
        if (!mode.equals("")) {
            cancellationDate = request.cancellationDate();
            if (cancellationDate == null || cancellationDate.isBlank()) {
                throw new IllegalStateException("Cancellation requested but date not provided");
            }
        }
        String transactionLocator = "";
        String transactionState = "";
        switch (mode.toUpperCase(Locale.ROOT)) {
            case "DRAFT" ->
            {
                log.info("Mode=DRAFT → creating draft cancellation only");
                try {
                    transactionLocator = createDraftCancellation(
                            ctx,
                            BASE_URL,
                            policyLocator,
                            API_TOKEN,
                            cancellationDate
                    );
                    transactionState = "Draft";
                } catch (Exception e) {
                    log.error("Error creating draft cancellation for policy {}", policyLocator, e);

                }
            }

            case "ISSUE" ->{
                log.info("Mode=ISSUE → creating draft + issuing cancellation");
                try {
                    transactionLocator = createDraftCancellation(
                            ctx,
                            BASE_URL,
                            policyLocator,
                            API_TOKEN,
                            cancellationDate
                    );
                    transactionState = "Draft";
                } catch (Exception e) {
                    log.error("Error creating draft cancellation for policy {}", policyLocator, e);
                }
                if (transactionLocator != null && !transactionLocator.isBlank()) {
                    try {
                        issueCancellation(
                                ctx,
                                BASE_URL,
                                policyLocator,
                                API_TOKEN,
                                transactionLocator
                        );
                        transactionState = "Issued";
                    } catch (Exception e) {
                        log.error("Error issuing cancellation for policy {}", policyLocator, e);

                    }
                } else {
                    log.warn("Skipping issue step because draft cancellation was not created successfully for policy {}",
                            policyLocator);
                    }
                }

            //case "PREVIEW" -> {} TODO: do cancellation preview next

            case "" -> {
                log.info("No cancellation instructions for mode='{}'; auxData creation only", mode);
            }

        }



        return CancellationInvoicesLoadInvoiceAuxDataResponse.builder()
                .result(true)
                .transactionLocator(transactionLocator)
                .transactionState(transactionState)
                .build();
    }

    /**
     * Fetch all invoices for a policy (no filtering here).
     * Endpoint: GET /billing/{tenantLocator}/invoices/policies/{policyLocator}/list
     */
    private List<JsonNode> fetchPaidInvoicesForPolicy(
            PluginExecutionContext ctx,
            String baseUrl,
            String policyLocator,
            String apiToken) throws Exception {

        var tenantUuid = ctx.getTenantLocator()
                .orElseThrow(() -> new IllegalStateException("Tenant locator missing"));
        String tenantLocator = tenantUuid.toString();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = String.format(
                "%s/billing/%s/invoices/policies/%s/list?offset=0&count=100",
                baseUrl,
                tenantLocator,
                policyLocator
        );

        log.info("Fetching invoices for policy {} via {}", policyLocator, url);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response =
                HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Invoice list call failed, status="
                    + response.statusCode() + ", body=" + response.body());
        }

        String body = response.body();
        log.info("Invoice list raw response for policy {}: {}", policyLocator, body);

        JsonNode root = MAPPER.readTree(body);
        if (!root.isArray()) {
            throw new IllegalStateException("Expected invoice list to be an array");
        }

        List<JsonNode> invoices = new ArrayList<>();
        for (JsonNode inv : root) {
            invoices.add(inv);
        }

        return invoices;
    }

    /**
     * Fetch a single invoice with its items:
     * GET /billing/{tenantLocator}/invoices/{locator}
     */
    private JsonNode fetchInvoiceWithItems(
            PluginExecutionContext ctx,
            String baseUrl,
            String invoiceLocator,
            String apiToken) throws Exception {

        var tenantUuid = ctx.getTenantLocator()
                .orElseThrow(() -> new IllegalStateException("Tenant locator missing"));
        String tenantLocator = tenantUuid.toString();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = String.format(
                "%s/billing/%s/invoices/%s",
                baseUrl,
                tenantLocator,
                invoiceLocator
        );

        log.info("Fetching invoice with items: {}", url);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response =
                HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Fetch invoice failed, status="
                    + response.statusCode() + ", body=" + response.body());
        }

        return MAPPER.readTree(response.body());
    }

    /**
     * Write totals into Aux Data on the policy using EC Aux Data API:
     * PUT /auxdata/{tenantLocator}/auxdata/{locator}
     *
     * key = "paidInvoiceTotals"
     * value = JSON string: { "totalPremiumCollected": "...", "totalTaxCollected": "..." }
     */
    private void writePaidTotalsAuxData(
            PluginExecutionContext ctx,
            String baseUrl,
            String policyLocator,
            String apiToken,
            BigDecimal totalPremiumCollected,
            BigDecimal totalTaxCollected) throws Exception {

        var tenantUuid = ctx.getTenantLocator()
                .orElseThrow(() -> new IllegalStateException("Tenant locator missing"));
        String tenantLocator = tenantUuid.toString();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = String.format(
                "%s/auxdata/%s/auxdata/%s",
                baseUrl,
                tenantLocator,
                policyLocator
        );

        // Value we want to store as a JSON string
        ObjectNode valueJson = MAPPER.createObjectNode();
        valueJson.put("totalPremiumCollected", totalPremiumCollected.toPlainString());
        valueJson.put("totalTaxCollected", totalTaxCollected.toPlainString());

        // AuxDataSetCreateRequest:
        // {
        //   "auxData": [
        //     { "key": "paidInvoiceTotals", "value": "{...}", "uiType": "normal" }
        //   ]
        // }
        ObjectNode auxEntry = MAPPER.createObjectNode();
        auxEntry.put("key", "paidInvoiceTotals");
        auxEntry.put("value", MAPPER.writeValueAsString(valueJson));
        auxEntry.put("uiType", "normal");

        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode auxArray = body.putArray("auxData");
        auxArray.add(auxEntry);

        String bodyString = MAPPER.writeValueAsString(body);

        log.info("PUT AuxData for policy {} via {}", policyLocator, url);
        log.debug("AuxData payload: {}", bodyString);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(bodyString))
                .build();

        HttpResponse<String> response =
                HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("AuxData PUT failed, status="
                    + response.statusCode() + ", body=" + response.body());
        }

        log.info("Successfully wrote paidInvoiceTotals aux data for policy {}", policyLocator);
    }

    private void writeCancellationCommentsAuxData(
            PluginExecutionContext ctx,
            String baseUrl,
            String policyLocator,
            String apiToken,
            String cancellationComments) throws Exception {

        var tenantUuid = ctx.getTenantLocator()
                .orElseThrow(() -> new IllegalStateException("Tenant locator missing"));
        String tenantLocator = tenantUuid.toString();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = String.format(
                "%s/auxdata/%s/auxdata/%s",
                baseUrl,
                tenantLocator,
                policyLocator
        );

        // AuxDataSetCreateRequest:
        // {
        //   "auxData": [
        //     { "key": "cancellationComments", "value": "{...}", "uiType": "normal" }
        //   ]
        // }
        ObjectNode auxEntry = MAPPER.createObjectNode();
        auxEntry.put("key", "cancellationComments");
        auxEntry.put("value", cancellationComments);
        auxEntry.put("uiType", "normal");

        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode auxArray = body.putArray("auxData");
        auxArray.add(auxEntry);

        String bodyString = MAPPER.writeValueAsString(body);

        log.info("PUT AuxData for policy {} via {}", policyLocator, url);
        log.debug("AuxData payload: {}", bodyString);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(bodyString))
                .build();

        HttpResponse<String> response =
                HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("AuxData PUT failed, status="
                    + response.statusCode() + ", body=" + response.body());
        }

        log.info("Successfully wrote cancellation comments aux data for policy {}", policyLocator);
    }

    private String createDraftCancellation(
            PluginExecutionContext ctx,
            String baseUrl,
            String policyLocator,
            String apiToken,
            String cancellationDate) throws Exception {

        var tenantUuid = ctx.getTenantLocator()
                .orElseThrow(() -> new IllegalStateException("Tenant locator missing"));
        String tenantLocator = tenantUuid.toString();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = String.format(
                "%s/policy/%s/policies/%s/cancel",
                baseUrl,
                tenantLocator,
                policyLocator
        );

        // AuxDataSetCreateRequest:
        // {
        //   "transaction": [
        //     { "action": "params", "effectiveTime": "cancellationDate" }
        //   ]
        // }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("action", "params");
        body.put("effectiveTime", cancellationDate);

        String bodyString = MAPPER.writeValueAsString(body);

        log.info("PATCH cancellation draft for policy {} via {}", policyLocator, url);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyString))
                .build();

        HttpResponse<String> response =
                HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Cancellation PATCH failed, status="
                    + response.statusCode() + ", body=" + response.body());
        }
        JsonNode root = MAPPER.readTree(response.body());
        String transactionLocator = root.path("locator").asText(null);
        if (transactionLocator == null || transactionLocator.isBlank()) {
            throw new IllegalStateException("Draft cancellation response missing locator");
        }

        log.info("Created draft cancellation transaction {} for policy {}",
                transactionLocator, policyLocator);

        return transactionLocator;
    }

    private void issueCancellation(
            PluginExecutionContext ctx,
            String baseUrl,
            String policyLocator,
            String apiToken,
            String transactionLocator) throws Exception {

        var tenantUuid = ctx.getTenantLocator()
                .orElseThrow(() -> new IllegalStateException("Tenant locator missing"));
        String tenantLocator = tenantUuid.toString();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = String.format(
                "%s/policy/%s/transactions/%s/issue",
                baseUrl,
                tenantLocator,
                transactionLocator
        );

        log.info("PATCH cancellation issue for policy {} via {}", policyLocator, url);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response =
                HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Cancellation PATCH failed, status="
                    + response.statusCode() + ", body=" + response.body());
        }

        log.info("Issued cancellation transaction {} for policy {}",
                transactionLocator, policyLocator);
    }
}