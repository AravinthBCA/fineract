/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.fineract.client.models.AdvancedPaymentData;
import org.apache.fineract.client.models.BusinessDateRequest;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PaymentAllocationOrder;
import org.apache.fineract.client.models.PostClientsResponse;
import org.apache.fineract.client.models.PostCreateRescheduleLoansRequest;
import org.apache.fineract.client.models.PostCreateRescheduleLoansResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansLoanIdTransactionsRequest;
import org.apache.fineract.client.models.PostLoansLoanIdTransactionsTransactionIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.client.models.PostUpdateRescheduleLoansRequest;
import org.apache.fineract.client.util.CallFailedRuntimeException;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.integrationtests.common.BusinessDateHelper;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.GlobalConfigurationHelper;
import org.apache.fineract.integrationtests.common.LoanRescheduleRequestHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.loans.LoanProductTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanTestLifecycleExtension;
import org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.AdvancedPaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.FineractStyleLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleProcessingType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanproduct.domain.PaymentAllocationType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(LoanTestLifecycleExtension.class)
public class AdvancedPaymentAllocationLoanRepaymentScheduleTest {

    private static final Logger LOG = LoggerFactory.getLogger(AdvancedPaymentAllocationLoanRepaymentScheduleTest.class);
    private static final String DATETIME_PATTERN = "dd MMMM yyyy";
    private static ResponseSpecification responseSpec;
    private static RequestSpecification requestSpec;
    private static BusinessDateHelper businessDateHelper;
    private static LoanTransactionHelper loanTransactionHelper;
    private static AccountHelper accountHelper;
    private static Integer commonLoanProductId;
    private static PostClientsResponse client;
    private static LoanRescheduleRequestHelper loanRescheduleRequestHelper;

    @BeforeAll
    public static void setup() {
        Utils.initializeRESTAssured();
        requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        requestSpec.header("Fineract-Platform-TenantId", "default");
        responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        loanTransactionHelper = new LoanTransactionHelper(requestSpec, responseSpec);
        businessDateHelper = new BusinessDateHelper();
        accountHelper = new AccountHelper(requestSpec, responseSpec);
        ClientHelper clientHelper = new ClientHelper(requestSpec, responseSpec);
        loanRescheduleRequestHelper = new LoanRescheduleRequestHelper(requestSpec, responseSpec);

        final Account assetAccount = accountHelper.createAssetAccount();
        final Account incomeAccount = accountHelper.createIncomeAccount();
        final Account expenseAccount = accountHelper.createExpenseAccount();
        final Account overpaymentAccount = accountHelper.createLiabilityAccount();

        commonLoanProductId = createLoanProduct("500", "15", "4", true, "25", true, LoanScheduleType.PROGRESSIVE,
                LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
        client = clientHelper.createClient(ClientHelper.defaultClientCreationRequest());
    }

    // UC1: Simple payments
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay installments on due dates

    @Test
    public void uc1() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 250.0, 250.0, 250.0, 250.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 125.0, 125.0, 0.0, 250.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 125.0, 375.0, 125.0, 375.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 125.0, 0.0, 125.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }
    // UC2: Overpayment1
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Overpay 2nd installment
    // 4. Overpay loan

    @Test
    public void uc2() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(150.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 225.0, 275.0, 225.0, 275.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 150.0, 150.0, 0.0, 225.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 100.0, 400.0, 100.0, 400.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 125.0, 0.0, 100.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, 25.0);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 100.0, 25.0, 0.0);
            assertTrue(loanDetails.getStatus().getOverpaid());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }
    // UC3: Overpayment2
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Overpay 2nd installment

    @Test
    public void uc3() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeGoodwillCredit(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(150.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 225.0, 275.0, 225.0, 275.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 150.0, 150.0, 0.0, 225.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 100.0, 400.0, 100.0, 400.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 125.0, 0.0, 100.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, 25.0);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 100.0, 25.0, 0.0);
            assertTrue(loanDetails.getStatus().getOverpaid());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }
    // UC4: Delinquent balance
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment - fails
    // 3. Pay 1st installment - fails
    // 4. Pay rest manually

    @Test
    public void uc4() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("01 January 2023")
                            .transactionAmount(0.0).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(2).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023")
                            .transactionAmount(0.0).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);

            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("20 January 2023").locale("en").transactionAmount(100.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 400.0, 100.0, 400.0, 100.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 100.0, 25.0, 0.0, 100.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 100.0, 100.0, 0.0, 400.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(40.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 360.0, 140.0, 360.0, 140.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 15.0, 110.0, 0.0, 15.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 40.0, 40.0, 0.0, 360.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(360.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 360.0, 360.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC5: Refund past due
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment - fails
    // 3. Partial Merchant refund
    // 4. Pay rest on time
    @Test
    public void uc5() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("01 January 2023")
                            .transactionAmount(0.0).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("8 January 2023").locale("en").transactionAmount(300.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 200.0, 300.0, 200.0, 300.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 58.33, 66.67, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 58.33, 66.67, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 58.34, 66.66, 58.34, 0.0);
            validateLoanTransaction(loanDetails, 2, 300.0, 300.0, 0.0, 200.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(66.67));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 133.33, 366.67, 133.33, 366.67, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 58.33, 66.67, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 58.34, 66.66, 58.34, 0.0);
            validateLoanTransaction(loanDetails, 3, 66.67, 66.67, 0.0, 133.33);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(66.67));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 66.66, 433.34, 66.66, 433.34, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 58.34, 66.66, 58.34, 0.0);
            validateLoanTransaction(loanDetails, 4, 66.67, 66.67, 0.0, 66.66);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(66.66));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 58.33, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 58.34, 0.0);
            validateLoanTransaction(loanDetails, 5, 66.66, 66.66, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC7: Refund & due reamortization
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay 1st installment on time
    // 4. Merchant issued refund
    // 4. Pay rest on time
    @Test
    public void uc7() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());

            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 250.0, 250.0, 250.0, 250.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 125.0, 125.0, 0.0, 250.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(200.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 50.0, 450.0, 50.0, 450.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 100.0, 25.0, 100.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 100.0, 25.0, 100.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 200, 200.0, 0.0, 50.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(25.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 25.0, 475.0, 25.0, 475.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 100.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 100.0, 25.0, 100.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 25.0, 25.0, 0.0, 25.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(25.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 100.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 100.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 25.0, 25.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC8: Refund after due & past due
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment - fails
    // 3. Pay 1st installment on time - fails
    // 4. Merchant issued refund next day
    // 4. Pay rest on time
    @Test
    public void uc8() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("01 January 2023")
                            .transactionAmount(0.0).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(2).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023")
                            .transactionAmount(0.0).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("17 January 2023").locale("en").transactionAmount(300.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 200.0, 300.0, 200.0, 300.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 300.0, 300.0, 0.0, 200.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(100.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 100.0, 400.0, 100.0, 400.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 100.0, 100.0, 0.0, 100.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(100.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 100.0, 100.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC9: Refund next installment
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Refund
    // 4. Pay rest on time
    @Test
    public void uc9() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makePayoutRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("05 January 2023").locale("en").transactionAmount(200.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 175.0, 325.0, 175.0, 325.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 200.0, 200.0, 0.0, 175.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(50.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 125.0, 375.0, 125.0, 375.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 50.0, 50.0, 0.0, 125.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC10: Refund PD and next installment
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. 1st installment on time - fails
    // 4. Payout refund issued refund next day
    // 4. Pay rest on time
    @Test
    public void uc10() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(2).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023")
                            .transactionAmount(0.0).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makePayoutRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("17 January 2023").locale("en").transactionAmount(200.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 175.0, 325.0, 175.0, 325.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 200.0, 200.0, 0.0, 175.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(50.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 125.0, 375.0, 125.0, 375.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 50.0, 50.0, 0.0, 125.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 125.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC11: Refund Past, pay in advance installments
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment - fails
    // 3. Payout refund issued refund next day
    // 4. Pay rest on time
    @Test
    public void uc11() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("01 January 2023")
                            .transactionAmount(0.0).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makePayoutRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 January 2023").locale("en").transactionAmount(400.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 100.0, 400.0, 100.0, 400.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 25.0, 100.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 400.0, 400.0, 0.0, 100.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(100.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 25.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 100.0, 100.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC12: Refund last installment
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Goodwill credit in advance
    // 4. Pay rest on time
    @Test
    public void uc12() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeGoodwillCredit(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("08 January 2023").locale("en").transactionAmount(200.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 175.0, 325.0, 175.0, 325.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 200.0, 200.0, 0.0, 175.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 50.0, 450.0, 50.0, 450.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 125.0, 0.0, 50.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(50.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 75.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 125.0, 0.0, 50.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC13: Due apply last installment
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay first installment on time - fails
    // 4. Goodwill credit(due and in advance)
    // 5. Pay rest on time
    @Test
    public void uc13() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(2).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023")
                            .transactionAmount(0.0).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeGoodwillCredit(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("18 January 2023").locale("en").transactionAmount(200.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 175.0, 325.0, 175.0, 325.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 200.0, 200.0, 0.0, 175.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 50.0, 450.0, 50.0, 450.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 125.0, 0.0, 50.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(50.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 75.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 50.0, 50.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC14: Refund PD
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay first installment on time - fails
    // 4. Refund (DP due and in advance)
    // 5. Pay rest on time
    @Test
    public void uc14() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("01 January 2023")
                            .transactionAmount(0.0).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(2).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023")
                            .transactionAmount(0.0).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("17 January 2023").locale("en").transactionAmount(200.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 300.0, 200.0, 300.0, 200.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 75.0, 50.0, 0.0, 75.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 200.0, 200.0, 0.0, 300.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 175.0, 325.0, 175.0, 325.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 75.0, 50.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 125.0, 0.0, 175.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(175.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 50.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 175.0, 175.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC15: Goodwill credit PD
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay first installment on time - fails
    // 4. Refund (DP due and in advance)
    // 5. Pay rest on time
    @Test
    public void uc15() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("01 January 2023")
                            .transactionAmount(0.0).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeGoodwillCredit(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 January 2023").locale("en").transactionAmount(200.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 300.0, 200.0, 300.0, 200.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 200.0, 200.0, 0.0, 300.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 175.0, 325.0, 175.0, 325.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 125.0, 0.0, 175.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 50.0, 450.0, 50.0, 450.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 75.0, 50.0, 75.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 125.0, 0.0, 50.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(50.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 75.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 50.0, 50.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC17a: Full refund with CBR
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay first installment on time
    // 4. Full merchant issued refund
    // 5. CBR
    @Test
    public void uc17a() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("08 January 2023").locale("en").transactionAmount(500.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, 125.0);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 500.0, 375.0, 125.0, 0.0);
            assertTrue(loanDetails.getStatus().getOverpaid());

            loanTransactionHelper.makeCreditBalanceRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("09 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 0.0, 125.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC17b: Full refund with CBR
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay first installment on time
    // 4. Full payout refund
    // 5. CBR
    @Test
    public void uc17b() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makePayoutRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("08 January 2023").locale("en").transactionAmount(500.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, 125.0);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 500.0, 375.0, 125.0, 0.0);
            assertTrue(loanDetails.getStatus().getOverpaid());

            loanTransactionHelper.makeCreditBalanceRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("09 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 0.0, 125.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC17c: Full refund with CBR
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay first installment on time
    // 4. Full Goodwill credit
    // 5. CBR
    @Test
    public void uc17c() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.15").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeGoodwillCredit(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("08 January 2023").locale("en").transactionAmount(500.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, 125.0);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 500.0, 375.0, 125.0, 0.0);
            assertTrue(loanDetails.getStatus().getOverpaid());

            loanTransactionHelper.makeCreditBalanceRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("09 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 0.0, 125.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC18: Full refund with CBR (N+1)
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment
    // 3. Pay everything on time
    // 4. Full merchant issued refund (after maturity date)
    // 5. CBR
    @Test
    public void uc18() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.20").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 375.0, 125.0, 375.0, 125.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 125.0, 125.0, 0.0, 375.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 250.0, 250.0, 250.0, 250.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 2, 125.0, 125.0, 0.0, 250.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 125.0, 375.0, 125.0, 375.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 3, 125.0, 125.0, 0.0, 125.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(125.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("18 February 2023").locale("en").transactionAmount(500.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, 500.0);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 500.0, 0.0, 500.0, 0.0);
            assertTrue(loanDetails.getStatus().getOverpaid());

            loanTransactionHelper.makeCreditBalanceRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("20 February 2023").locale("en").transactionAmount(500.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 6, 500.0, 0.0, 500.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC24: Merchant issued credit reverse-replay
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment - fails
    // 3. Merchant issued credit
    // 4. Payments
    // 5. CBR
    // 6. Merchant issued credit - reversal
    // 7. Payments
    @Test
    public void uc24() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.20").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023")
                            .transactionAmount(0.0).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("02 January 2023").locale("en").transactionAmount(400.0));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 100.0, 400.0, 100.0, 400.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 91.67, 33.33, 91.67, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 91.67, 33.33, 91.67, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 91.66, 33.34, 91.66, 0.0);
            validateLoanTransaction(loanDetails, 2, 400.0, 400.0, 0.0, 100.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("04 January 2023").locale("en").transactionAmount(50.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 50.0, 450.0, 50.0, 450.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 108.34, 16.66, 108.34, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 91.66, 33.34, 91.66, 0.0);
            validateLoanTransaction(loanDetails, 3, 50.0, 50.0, 0.0, 50.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, 75.0);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 125.0, 50.0, 75.0, 0.0);
            assertTrue(loanDetails.getStatus().getOverpaid());

            loanTransactionHelper.makeCreditBalanceRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("18 January 2023").locale("en").transactionAmount(75.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 500.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateLoanTransaction(loanDetails, 5, 75.0, 0.0, 75.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());

            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(2).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("20 January 2023")
                            .transactionAmount(0.0).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 400.0, 175.0, 400.0, 175.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 50.0, 75.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 200.0, 0.0, 200.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("31 January 2023").locale("en").transactionAmount(275.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 125.0, 450.0, 125.0, 450.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 75.0);
            validateRepaymentPeriod(loanDetails, 3, 200.0, 200.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 6, 275.0, 275.0, 0.0, 125.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("15 February 2023").locale("en").transactionAmount(125.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 575.0, 0.0, 575.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 0.0, 75.0);
            validateRepaymentPeriod(loanDetails, 3, 200.0, 200.0, 0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 125.0, 0.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 7, 125.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC25: Merchant issued credit reverse-replay with uneven balances
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay down payment - fails
    // 3. Merchant issued credit
    // 4. Payments
    // 5. Merchant issued credit
    @Test
    public void uc25() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.20").dateFormat("yyyy.MM.dd").locale("en"));

            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), commonLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            loanTransactionHelper.reverseLoanTransaction(loanResponse.getLoanId(), loanDetails.getTransactions().get(1).getId(),
                    new PostLoansLoanIdTransactionsTransactionIdRequest().dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023")
                            .transactionAmount(0.0).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("02 January 2023").locale("en").transactionAmount(400.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 100.0, 400.0, 100.0, 400.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 91.67, 33.33, 91.67, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 91.67, 33.33, 91.67, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 91.66, 33.34, 91.66, 0.0);
            validateLoanTransaction(loanDetails, 2, 400.0, 400.0, 0.0, 100.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("04 January 2023").locale("en").transactionAmount(50.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 50.0, 450.0, 50.0, 450.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 108.34, 16.66, 108.34, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 91.66, 33.34, 91.66, 0.0);
            validateLoanTransaction(loanDetails, 3, 50.0, 50.0, 0.0, 50.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeMerchantIssuedRefund(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("06 January 2023").locale("en").transactionAmount(40.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 10.0, 490.0, 10.0, 490.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 115.0, 10.0, 115.0, 0.0);
            validateLoanTransaction(loanDetails, 4, 40.0, 40.0, 0.0, 10.0);
            assertTrue(loanDetails.getStatus().getActive());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC101: Multiple disbursement test
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan
    // 2. Pay over the down payment
    // 3. Disburse again
    @Test
    public void uc101() {
        try {

            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.20").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("500", "15", "4", true, "25", false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("04 January 2023").locale("en").transactionAmount(175.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 325.0, 175.0, 325.0, 175.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 50.0, 75.0, 50.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateLoanTransaction(loanDetails, 1, 175.0, 175.0, 0.0, 325.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("05 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 825.0, 175.0, 825.0, 175.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 250.0, 50.0, 200.0, 50.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 250.0, 0.0, 250.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, 250.0, 0.0, 250.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            // Add Charge Penalty
            Integer penalty = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanSpecifiedDueDateJSON(ChargesHelper.CHARGE_CALCULATION_TYPE_FLAT, "100", true));
            Integer penalty1LoanChargeId = loanTransactionHelper.addChargesForLoan(loanResponse.getLoanId().intValue(),
                    LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(String.valueOf(penalty), "22 February 2023", "100"));
            assertNotNull(penalty1LoanChargeId);

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 925.0, 175.0, 825.0, 175.0, null);
            validateRepaymentPeriod(loanDetails, 1, 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, 250.0, 50.0, 200.0, 50.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, 250.0, 0.0, 250.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, 250.0, 0.0, 250.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 6, LocalDate.of(2023, 2, 22), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 0.0, 100.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC102: Multiple disbursement & reschedule test
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan (500)
    // 2. Reschedule the 2nd period
    // 3. Repayment (450)
    // 3. Disburse again (250)
    // 4. Reschedule the 3rd period to be later than the 2nd disbursement
    @Test
    public void uc102() {
        try {

            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.20").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("500", "15", "4", true, "25", false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            PostCreateRescheduleLoansResponse rescheduleLoansResponse = loanRescheduleRequestHelper
                    .createLoanRescheduleRequest(new PostCreateRescheduleLoansRequest().loanId(loanDetails.getId()).locale("en")
                            .dateFormat(DATETIME_PATTERN).rescheduleReasonId(1L).rescheduleFromDate("16 January 2023")
                            .adjustedDueDate("31 January 2023").submittedOnDate("16 January 2023"));

            loanRescheduleRequestHelper.approveLoanRescheduleRequest(rescheduleLoansResponse.getResourceId(),
                    new PostUpdateRescheduleLoansRequest().approvedOnDate("16 January 2023").locale("en").dateFormat(DATETIME_PATTERN));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("16 January 2023").locale("en").transactionAmount(350.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 150.0, 350.0, 150.0, 350.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 125.0, 100.0, 25.0, 100.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("20 February 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(250.0)).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 400.0, 350.0, 400.0, 350.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 125.0, 100.0, 25.0, 100.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 20), 62.5, 0.0, 62.5, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 2), 312.5, 0.0, 312.5, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            rescheduleLoansResponse = loanRescheduleRequestHelper.createLoanRescheduleRequest(new PostCreateRescheduleLoansRequest()
                    .loanId(loanDetails.getId()).locale("en").dateFormat(DATETIME_PATTERN).rescheduleReasonId(1L)
                    .rescheduleFromDate("15 February 2023").adjustedDueDate("25 February 2023").submittedOnDate("15 February 2023"));

            loanRescheduleRequestHelper.approveLoanRescheduleRequest(rescheduleLoansResponse.getResourceId(),
                    new PostUpdateRescheduleLoansRequest().approvedOnDate("15 February 2023").locale("en").dateFormat(DATETIME_PATTERN));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 400.0, 350.0, 400.0, 350.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 125.0, 125.0, 0.0, 125.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 20), 62.5, 0.0, 62.5, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 25), 218.75, 100.0, 118.75, 100.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 12), 218.75, 0.0, 218.75, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC103: Advanced payment allocation, add charge after maturity -> N+1 installment, repay the loan
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan (500)
    // 2. Add charge after maturity date
    // 3. Fully repay the loan
    @Test
    public void uc103() {
        try {

            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("500", "15", "4", true, "25", false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            // Add Charge Penalty
            Integer penalty = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanSpecifiedDueDateJSON(ChargesHelper.CHARGE_CALCULATION_TYPE_FLAT, "100", true));
            Integer penalty1LoanChargeId = loanTransactionHelper.addChargesForLoan(loanResponse.getLoanId().intValue(),
                    LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(String.valueOf(penalty), "22 February 2023", "100"));
            assertNotNull(penalty1LoanChargeId);

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 600.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 2, 22), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 0.0, 100.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("22 February 2023").locale("en").transactionAmount(600.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 0.0, 600.0, 0.0, 500.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 2, 22), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 100.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getClosedObligationsMet());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC104: Advanced payment allocation, horizontal repayment processing
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan (500)
    // 2. Add charge for 3rd and 4th installments
    // 3. Pay little more than the charge of 1st installment
    // 4. Pay little more than the principal of 1st installment and charge of 2nd installment
    @Test
    public void uc104() {
        try {

            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("500", "15", "4", true, "25", false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            // Add Charge Penalty
            Integer penalty = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanSpecifiedDueDateJSON(ChargesHelper.CHARGE_CALCULATION_TYPE_FLAT, "25", true));
            loanTransactionHelper.addChargesForLoan(loanResponse.getLoanId().intValue(),
                    LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(String.valueOf(penalty), "20 January 2023", "25"));
            loanTransactionHelper.addChargesForLoan(loanResponse.getLoanId().intValue(),
                    LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(String.valueOf(penalty), "10 February 2023", "25"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 550.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 0.0, 25.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 0.0, 25.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("22 February 2023").locale("en").transactionAmount(260.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 290.0, 260.0, 250.0, 250.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 10.0, 15.0, 0.0, 0.0,
                    0.0, 0.0, 10.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 0.0, 25.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("22 February 2023").locale("en").transactionAmount(26.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 264.0, 286.0, 239.0, 261.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 125.0, 0.0, 0.0, 125.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 11.0, 114.0, 0.0, 0.0, 0.0, 25.0, 25.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 36.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 0.0, 25.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC105: Advanced payment allocation, vertical repayment processing
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Disburse the loan (500)
    // 2. Add charge for 3rd and 4th installments
    // 3. Pay little more than the charge of 1st installment
    // 4. Pay little more than the charges
    @Test
    public void uc105() {
        try {

            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("500", "15", "4", true, "25", false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.VERTICAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            final PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId,
                    BigDecimal.valueOf(500.0), 45, 15, 3, 0, "01 January 2023", "01 January 2023");

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(500)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(500.00)).locale("en"));

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 500.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            // Add Charge Penalty
            Integer penalty = ChargesHelper.createCharges(requestSpec, responseSpec,
                    ChargesHelper.getLoanSpecifiedDueDateJSON(ChargesHelper.CHARGE_CALCULATION_TYPE_FLAT, "25", true));
            loanTransactionHelper.addChargesForLoan(loanResponse.getLoanId().intValue(),
                    LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(String.valueOf(penalty), "20 January 2023", "25"));
            loanTransactionHelper.addChargesForLoan(loanResponse.getLoanId().intValue(),
                    LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(String.valueOf(penalty), "10 February 2023", "25"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 550.0, 0.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 0.0, 25.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 0.0, 25.0, 0.0, 0.0,
                    0.0, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("22 February 2023").locale("en").transactionAmount(26.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 524.0, 26.0, 500.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 25.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 25.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 1.0, 24.0, 0.0, 0.0,
                    0.0, 0.0, 1.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanTransactionHelper.makeLoanRepayment(loanResponse.getLoanId(), new PostLoansLoanIdTransactionsRequest()
                    .dateFormat(DATETIME_PATTERN).transactionDate("22 February 2023").locale("en").transactionAmount(26.0));
            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 498.0, 52.0, 498.0, 2.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 125.0, 2.0, 123.0, 0.0, 2.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 125.0, 0.0, 125.0, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 25.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 25.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 125.0, 0.0, 125.0, 0.0, 0.0, 0.0, 25.0, 25.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 25.0);
            assertTrue(loanDetails.getStatus().getActive());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC106: Advanced payment allocation, horizontal repayment processing, mixed grouping of allocation rules
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Create a Loan product, but the allocation rules are mixed -> expect validation error
    @Test
    public void uc106() {
        try {

            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            AdvancedPaymentData defaultPaymentAllocation = createDefaultPaymentAllocationWithMixedGrouping();

            ArrayList<HashMap<String, Object>> loanProductErrorData = createLoanProductGetError("500", "15", "4", false,
                    LoanScheduleType.PROGRESSIVE, LoanScheduleProcessingType.HORIZONTAL, defaultPaymentAllocation, assetAccount,
                    incomeAccount, expenseAccount, overpaymentAccount);
            assertNotNull(loanProductErrorData);
            assertEquals("mixed.due.type.allocation.rules.are.not.supported.with.horizontal.installment.processing",
                    loanProductErrorData.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE));
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC107: Advanced payment allocation, vertical repayment processing, mixed grouping of allocation rules
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Create a Loan product, but the allocation rules are mixed -> no validation error
    // 2. Change transaction processor while submitting loan -> expect validation error
    // 3. Change to HORIZONTAL loan schedule processing and allocation rules are mixed - > expect validation error
    @Test
    public void uc107() {
        try {

            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            AdvancedPaymentData defaultPaymentAllocation = createDefaultPaymentAllocationWithMixedGrouping();

            Integer localLoanProductId = createLoanProduct("500", "15", "4", false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.VERTICAL, defaultPaymentAllocation, assetAccount, incomeAccount, expenseAccount,
                    overpaymentAccount);
            assertNotNull(localLoanProductId);

            CallFailedRuntimeException exception = assertThrows(CallFailedRuntimeException.class,
                    () -> applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(500.0), 45, 15, 3, 0,
                            "01 January 2023", "01 January 2023", FineractStyleLoanRepaymentScheduleTransactionProcessor.STRATEGY_CODE,
                            LoanScheduleType.PROGRESSIVE.name(), LoanScheduleProcessingType.VERTICAL.name()));
            assertEquals(400, exception.getResponse().code());
            assertTrue(exception.getMessage().contains("supported.only.with.advanced.payment.allocation.strategy"));

            exception = assertThrows(CallFailedRuntimeException.class,
                    () -> applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(500.0), 45, 15, 3, 0,
                            "01 January 2023", "01 January 2023",
                            AdvancedPaymentScheduleTransactionProcessor.ADVANCED_PAYMENT_ALLOCATION_STRATEGY,
                            LoanScheduleType.PROGRESSIVE.name(), LoanScheduleProcessingType.HORIZONTAL.name()));
            assertEquals(400, exception.getResponse().code());
            assertTrue(exception.getMessage()
                    .contains("mixed.due.type.allocation.rules.are.not.supported.with.horizontal.installment.processing"));
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC108: Advanced payment allocation, progressive loan schedule handling, rounding test
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Create a Loan product, 1000 principal, across 3 installment
    // 2. Submit the loan, and check the generated repayment schedule
    @Test
    public void uc108() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("1000", "15", "3", false, null, false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            assertNotNull(localLoanProductId);

            PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(1000), 45,
                    15, 3, 0, "01 January 2023", "01 January 2023");

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 333.34, 0.0, 333.34, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getPendingApproval());

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(1000)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 333.34, 0.0, 333.34, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getWaitingForDisbursal());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(1000)).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 1000.0, 0.0, 1000.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 333.34, 0.0, 333.34, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(1000), 90, 15, 6, 0,
                    "01 January 2023", "01 January 2023");

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 17), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 6, LocalDate.of(2023, 4, 1), 166.65, 0.0, 166.65, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getPendingApproval());

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(1000)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 17), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 6, LocalDate.of(2023, 4, 1), 166.65, 0.0, 166.65, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getWaitingForDisbursal());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(1000)).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 1000.0, 0.0, 1000.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 17), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 6, LocalDate.of(2023, 4, 1), 166.65, 0.0, 166.65, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC109: Advanced payment allocation, cumulative loan schedule handling, rounding test
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Create a Loan product, 1000 principal, across 3 installment
    // 2. Submit the loan, and check the generated repayment schedule
    @Test
    public void uc109() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("1000", "15", "3", false, null, false, LoanScheduleType.CUMULATIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            assertNotNull(localLoanProductId);

            PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(1000), 45,
                    15, 3, 0, "01 January 2023", "01 January 2023");

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 333.34, 0.0, 333.34, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getPendingApproval());

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(1000)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 333.34, 0.0, 333.34, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getWaitingForDisbursal());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(1000)).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 1000.0, 0.0, 1000.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 333.33, 0.0, 333.33, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 333.34, 0.0, 333.34, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

            loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(1000), 90, 15, 6, 0,
                    "01 January 2023", "01 January 2023");

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 17), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 6, LocalDate.of(2023, 4, 1), 166.65, 0.0, 166.65, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getPendingApproval());

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(1000)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 17), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 6, LocalDate.of(2023, 4, 1), 166.65, 0.0, 166.65, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getWaitingForDisbursal());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(1000)).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 1000.0, 0.0, 1000.0, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 16), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 31), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 2, 15), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 3, 2), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 5, LocalDate.of(2023, 3, 17), 166.67, 0.0, 166.67, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 6, LocalDate.of(2023, 4, 1), 166.65, 0.0, 166.65, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());

        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC110: Advanced payment allocation, progressive loan schedule handling, rounding test
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Create a Loan product, 40.50 principal, across 4 installment (1 down payment, 3 normal installment)
    // 2. Submit the loan, and check the generated repayment schedule
    @Test
    public void uc110() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("40.50", "15", "3", true, "25", false, LoanScheduleType.PROGRESSIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            assertNotNull(localLoanProductId);

            PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(40.50),
                    45, 15, 3, 0, "01 January 2023", "01 January 2023");

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 10.12, 0.0, 10.12, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 10.12, 0.0, 10.12, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getPendingApproval());

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(40.5)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 10.12, 0.0, 10.12, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 10.12, 0.0, 10.12, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getWaitingForDisbursal());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(40.5)).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 40.5, 0.0, 40.5, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 10.12, 0.0, 10.12, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 10.12, 0.0, 10.12, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    // UC111: Advanced payment allocation, cumulative loan schedule handling, rounding test
    // ADVANCED_PAYMENT_ALLOCATION_STRATEGY
    // 1. Create a Loan product, 40.50 principal, across 4 installment (1 down payment, 3 normal installment)
    // 2. Submit the loan, and check the generated repayment schedule
    @Test
    public void uc111() {
        try {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.TRUE);
            businessDateHelper.updateBusinessDate(new BusinessDateRequest().type(BusinessDateType.BUSINESS_DATE.getName())
                    .date("2023.02.22").dateFormat("yyyy.MM.dd").locale("en"));

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account overpaymentAccount = accountHelper.createLiabilityAccount();
            Integer localLoanProductId = createLoanProduct("40.50", "15", "3", true, "25", false, LoanScheduleType.CUMULATIVE,
                    LoanScheduleProcessingType.HORIZONTAL, assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
            assertNotNull(localLoanProductId);

            PostLoansResponse loanResponse = applyForLoanApplication(client.getClientId(), localLoanProductId, BigDecimal.valueOf(40.50),
                    45, 15, 3, 0, "01 January 2023", "01 January 2023");

            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 10.12, 0.0, 10.12, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 10.12, 0.0, 10.12, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getPendingApproval());

            loanTransactionHelper.approveLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(40.5)).dateFormat(DATETIME_PATTERN)
                            .approvedOnDate("01 January 2023").locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 10.12, 0.0, 10.12, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 10.12, 0.0, 10.12, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getWaitingForDisbursal());

            loanTransactionHelper.disburseLoan(loanResponse.getLoanId(),
                    new PostLoansLoanIdRequest().actualDisbursementDate("01 January 2023").dateFormat(DATETIME_PATTERN)
                            .transactionAmount(BigDecimal.valueOf(40.5)).locale("en"));

            loanDetails = loanTransactionHelper.getLoanDetails(loanResponse.getLoanId());
            validateLoanSummaryBalances(loanDetails, 40.5, 0.0, 40.5, 0.0, null);
            validateRepaymentPeriod(loanDetails, 1, LocalDate.of(2023, 1, 1), 10.12, 0.0, 10.12, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 2, LocalDate.of(2023, 1, 16), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 3, LocalDate.of(2023, 1, 31), 10.13, 0.0, 10.13, 0.0, 0.0);
            validateRepaymentPeriod(loanDetails, 4, LocalDate.of(2023, 2, 15), 10.12, 0.0, 10.12, 0.0, 0.0);
            assertTrue(loanDetails.getStatus().getActive());
        } finally {
            GlobalConfigurationHelper.updateIsBusinessDateEnabled(requestSpec, responseSpec, Boolean.FALSE);
        }
    }

    private static void validateLoanSummaryBalances(GetLoansLoanIdResponse loanDetails, Double totalOutstanding, Double totalRepayment,
            Double principalOutstanding, Double principalPaid, Double totalOverpaid) {
        assertEquals(totalOutstanding, loanDetails.getSummary().getTotalOutstanding());
        assertEquals(totalRepayment, loanDetails.getSummary().getTotalRepayment());
        assertEquals(principalOutstanding, loanDetails.getSummary().getPrincipalOutstanding());
        assertEquals(principalPaid, loanDetails.getSummary().getPrincipalPaid());
        assertEquals(totalOverpaid, loanDetails.getTotalOverpaid());
    }

    private static List<PaymentAllocationOrder> getPaymentAllocationOrder(PaymentAllocationType... paymentAllocationTypes) {
        AtomicInteger integer = new AtomicInteger(1);
        return Arrays.stream(paymentAllocationTypes).map(pat -> {
            PaymentAllocationOrder paymentAllocationOrder = new PaymentAllocationOrder();
            paymentAllocationOrder.setPaymentAllocationRule(pat.name());
            paymentAllocationOrder.setOrder(integer.getAndIncrement());
            return paymentAllocationOrder;
        }).collect(Collectors.toList());
    }

    private static AdvancedPaymentData createDefaultPaymentAllocationWithMixedGrouping() {
        AdvancedPaymentData advancedPaymentData = new AdvancedPaymentData();
        advancedPaymentData.setTransactionType("DEFAULT");
        advancedPaymentData.setFutureInstallmentAllocationRule("NEXT_INSTALLMENT");

        List<PaymentAllocationOrder> paymentAllocationOrders = getPaymentAllocationOrder(PaymentAllocationType.DUE_PENALTY,
                PaymentAllocationType.PAST_DUE_FEE, PaymentAllocationType.PAST_DUE_PRINCIPAL, PaymentAllocationType.PAST_DUE_INTEREST,
                PaymentAllocationType.PAST_DUE_PENALTY, PaymentAllocationType.DUE_FEE, PaymentAllocationType.DUE_PRINCIPAL,
                PaymentAllocationType.DUE_INTEREST, PaymentAllocationType.IN_ADVANCE_PENALTY, PaymentAllocationType.IN_ADVANCE_FEE,
                PaymentAllocationType.IN_ADVANCE_PRINCIPAL, PaymentAllocationType.IN_ADVANCE_INTEREST);

        advancedPaymentData.setPaymentAllocationOrder(paymentAllocationOrders);
        return advancedPaymentData;
    }

    private static AdvancedPaymentData createDefaultPaymentAllocation() {
        AdvancedPaymentData advancedPaymentData = new AdvancedPaymentData();
        advancedPaymentData.setTransactionType("DEFAULT");
        advancedPaymentData.setFutureInstallmentAllocationRule("NEXT_INSTALLMENT");

        List<PaymentAllocationOrder> paymentAllocationOrders = getPaymentAllocationOrder(PaymentAllocationType.PAST_DUE_PENALTY,
                PaymentAllocationType.PAST_DUE_FEE, PaymentAllocationType.PAST_DUE_PRINCIPAL, PaymentAllocationType.PAST_DUE_INTEREST,
                PaymentAllocationType.DUE_PENALTY, PaymentAllocationType.DUE_FEE, PaymentAllocationType.DUE_PRINCIPAL,
                PaymentAllocationType.DUE_INTEREST, PaymentAllocationType.IN_ADVANCE_PENALTY, PaymentAllocationType.IN_ADVANCE_FEE,
                PaymentAllocationType.IN_ADVANCE_PRINCIPAL, PaymentAllocationType.IN_ADVANCE_INTEREST);

        advancedPaymentData.setPaymentAllocationOrder(paymentAllocationOrders);
        return advancedPaymentData;
    }

    private static AdvancedPaymentData createPaymentAllocation(String transactionType, String futureInstallmentAllocationRule) {
        AdvancedPaymentData advancedPaymentData = new AdvancedPaymentData();
        advancedPaymentData.setTransactionType(transactionType);
        advancedPaymentData.setFutureInstallmentAllocationRule(futureInstallmentAllocationRule);

        List<PaymentAllocationOrder> paymentAllocationOrders = getPaymentAllocationOrder(PaymentAllocationType.PAST_DUE_PENALTY,
                PaymentAllocationType.PAST_DUE_FEE, PaymentAllocationType.PAST_DUE_PRINCIPAL, PaymentAllocationType.PAST_DUE_INTEREST,
                PaymentAllocationType.DUE_PENALTY, PaymentAllocationType.DUE_FEE, PaymentAllocationType.DUE_PRINCIPAL,
                PaymentAllocationType.DUE_INTEREST, PaymentAllocationType.IN_ADVANCE_PENALTY, PaymentAllocationType.IN_ADVANCE_FEE,
                PaymentAllocationType.IN_ADVANCE_PRINCIPAL, PaymentAllocationType.IN_ADVANCE_INTEREST);

        advancedPaymentData.setPaymentAllocationOrder(paymentAllocationOrders);
        return advancedPaymentData;
    }

    private static Integer createLoanProduct(final String principal, final String repaymentAfterEvery, final String numberOfRepayments,
            boolean autoPayForDownPayment, LoanScheduleType loanScheduleType, LoanScheduleProcessingType loanScheduleProcessingType,
            AdvancedPaymentData allocationRuleData, final Account... accounts) {
        LOG.info("------------------------------CREATING NEW LOAN PRODUCT ---------------------------------------");
        final String loanProductJSON = new LoanProductTestBuilder().withMinPrincipal(principal).withPrincipal(principal)
                .withRepaymentTypeAsDays().withRepaymentAfterEvery(repaymentAfterEvery).withNumberOfRepayments(numberOfRepayments)
                .withEnableDownPayment(true, "25", autoPayForDownPayment).withinterestRatePerPeriod("0")
                .withInterestRateFrequencyTypeAsMonths()
                .withRepaymentStrategy(AdvancedPaymentScheduleTransactionProcessor.ADVANCED_PAYMENT_ALLOCATION_STRATEGY)
                .withAmortizationTypeAsEqualPrincipalPayment().withInterestTypeAsFlat().withAccountingRulePeriodicAccrual(accounts)
                .addAdvancedPaymentAllocation(allocationRuleData).withInterestCalculationPeriodTypeAsRepaymentPeriod(true)
                .withInterestTypeAsDecliningBalance().withMultiDisburse().withDisallowExpectedDisbursements(true)
                .withLoanScheduleType(loanScheduleType).withLoanScheduleProcessingType(loanScheduleProcessingType).withDaysInMonth("30")
                .withDaysInYear("365").withMoratorium("0", "0").build(null);
        return loanTransactionHelper.getLoanProductId(loanProductJSON);
    }

    private static ArrayList<HashMap<String, Object>> createLoanProductGetError(final String principal, final String repaymentAfterEvery,
            final String numberOfRepayments, boolean autoPayForDownPayment, LoanScheduleType loanScheduleType,
            LoanScheduleProcessingType loanScheduleProcessingType, AdvancedPaymentData allocationRuleData, final Account... accounts) {
        LOG.info("------------------------------CREATING NEW LOAN PRODUCT ---------------------------------------");
        final String loanProductJSON = new LoanProductTestBuilder().withMinPrincipal(principal).withPrincipal(principal)
                .withRepaymentTypeAsDays().withRepaymentAfterEvery(repaymentAfterEvery).withNumberOfRepayments(numberOfRepayments)
                .withEnableDownPayment(true, "25", autoPayForDownPayment).withinterestRatePerPeriod("0")
                .withInterestRateFrequencyTypeAsMonths()
                .withRepaymentStrategy(AdvancedPaymentScheduleTransactionProcessor.ADVANCED_PAYMENT_ALLOCATION_STRATEGY)
                .withAmortizationTypeAsEqualPrincipalPayment().withInterestTypeAsFlat().withAccountingRulePeriodicAccrual(accounts)
                .addAdvancedPaymentAllocation(allocationRuleData).withInterestCalculationPeriodTypeAsRepaymentPeriod(true)
                .withInterestTypeAsDecliningBalance().withMultiDisburse().withDisallowExpectedDisbursements(true)
                .withLoanScheduleType(loanScheduleType).withLoanScheduleProcessingType(loanScheduleProcessingType).withDaysInMonth("30")
                .withDaysInYear("365").withMoratorium("0", "0").build(null);
        LoanTransactionHelper loanTransactionHelperBadRequest = new LoanTransactionHelper(requestSpec,
                new ResponseSpecBuilder().expectStatusCode(400).build());
        return loanTransactionHelperBadRequest.getLoanProductError(loanProductJSON, CommonConstants.RESPONSE_ERROR);
    }

    private static Integer createLoanProduct(final String principal, final String repaymentAfterEvery, final String numberOfRepayments,
            boolean downPaymentEnabled, String downPaymentPercentage, boolean autoPayForDownPayment, LoanScheduleType loanScheduleType,
            LoanScheduleProcessingType loanScheduleProcessingType, final Account... accounts) {
        AdvancedPaymentData defaultAllocation = createDefaultPaymentAllocation();
        AdvancedPaymentData goodwillCreditAllocation = createPaymentAllocation("GOODWILL_CREDIT", "LAST_INSTALLMENT");
        AdvancedPaymentData merchantIssuedRefundAllocation = createPaymentAllocation("MERCHANT_ISSUED_REFUND", "REAMORTIZATION");
        AdvancedPaymentData payoutRefundAllocation = createPaymentAllocation("PAYOUT_REFUND", "NEXT_INSTALLMENT");
        LOG.info("------------------------------CREATING NEW LOAN PRODUCT ---------------------------------------");
        final String loanProductJSON = new LoanProductTestBuilder().withMinPrincipal(principal).withPrincipal(principal)
                .withRepaymentTypeAsDays().withRepaymentAfterEvery(repaymentAfterEvery).withNumberOfRepayments(numberOfRepayments)
                .withEnableDownPayment(downPaymentEnabled, downPaymentPercentage, autoPayForDownPayment).withinterestRatePerPeriod("0")
                .withInterestRateFrequencyTypeAsMonths()
                .withRepaymentStrategy(AdvancedPaymentScheduleTransactionProcessor.ADVANCED_PAYMENT_ALLOCATION_STRATEGY)
                .withAmortizationTypeAsEqualPrincipalPayment().withInterestTypeAsFlat().withAccountingRulePeriodicAccrual(accounts)
                .addAdvancedPaymentAllocation(defaultAllocation, goodwillCreditAllocation, merchantIssuedRefundAllocation,
                        payoutRefundAllocation)
                .withInterestCalculationPeriodTypeAsRepaymentPeriod(true).withInterestTypeAsDecliningBalance().withMultiDisburse()
                .withDisallowExpectedDisbursements(true).withLoanScheduleType(loanScheduleType)
                .withLoanScheduleProcessingType(loanScheduleProcessingType).withDaysInMonth("30").withDaysInYear("365")
                .withMoratorium("0", "0").build(null);
        return loanTransactionHelper.getLoanProductId(loanProductJSON);
    }

    private static void validateRepaymentPeriod(GetLoansLoanIdResponse loanDetails, Integer index, LocalDate dueDate, double principalDue,
            double principalPaid, double principalOutstanding, double paidInAdvance, double paidLate) {
        GetLoansLoanIdRepaymentPeriod period = loanDetails.getRepaymentSchedule().getPeriods().stream()
                .filter(p -> Objects.equals(p.getPeriod(), index)).findFirst().orElseThrow();
        assertEquals(dueDate, period.getDueDate());
        assertEquals(principalDue, period.getPrincipalDue());
        assertEquals(principalPaid, period.getPrincipalPaid());
        assertEquals(principalOutstanding, period.getPrincipalOutstanding());
        assertEquals(paidInAdvance, period.getTotalPaidInAdvanceForPeriod());
        assertEquals(paidLate, period.getTotalPaidLateForPeriod());
    }

    private static void validateRepaymentPeriod(GetLoansLoanIdResponse loanDetails, Integer index, double principalDue,
            double principalPaid, double principalOutstanding, double paidInAdvance, double paidLate) {
        GetLoansLoanIdRepaymentPeriod period = loanDetails.getRepaymentSchedule().getPeriods().stream()
                .filter(p -> Objects.equals(p.getPeriod(), index)).findFirst().orElseThrow();
        assertEquals(principalDue, period.getPrincipalDue());
        assertEquals(principalPaid, period.getPrincipalPaid());
        assertEquals(principalOutstanding, period.getPrincipalOutstanding());
        assertEquals(paidInAdvance, period.getTotalPaidInAdvanceForPeriod());
        assertEquals(paidLate, period.getTotalPaidLateForPeriod());
    }

    private static void validateRepaymentPeriod(GetLoansLoanIdResponse loanDetails, Integer index, LocalDate dueDate, double principalDue,
            double principalPaid, double principalOutstanding, double feeDue, double feePaid, double feeOutstanding, double penaltyDue,
            double penaltyPaid, double penaltyOutstanding, double interestDue, double interestPaid, double interestOutstanding,
            double paidInAdvance, double paidLate) {
        GetLoansLoanIdRepaymentPeriod period = loanDetails.getRepaymentSchedule().getPeriods().stream()
                .filter(p -> Objects.equals(p.getPeriod(), index)).findFirst().orElseThrow();
        assertEquals(dueDate, period.getDueDate());
        assertEquals(principalDue, period.getPrincipalDue());
        assertEquals(principalPaid, period.getPrincipalPaid());
        assertEquals(principalOutstanding, period.getPrincipalOutstanding());
        assertEquals(feeDue, period.getFeeChargesDue());
        assertEquals(feePaid, period.getFeeChargesPaid());
        assertEquals(feeOutstanding, period.getFeeChargesOutstanding());
        assertEquals(penaltyDue, period.getPenaltyChargesDue());
        assertEquals(penaltyPaid, period.getPenaltyChargesPaid());
        assertEquals(penaltyOutstanding, period.getPenaltyChargesOutstanding());
        assertEquals(interestDue, period.getInterestDue());
        assertEquals(interestPaid, period.getInterestPaid());
        assertEquals(interestOutstanding, period.getInterestOutstanding());
        assertEquals(paidInAdvance, period.getTotalPaidInAdvanceForPeriod());
        assertEquals(paidLate, period.getTotalPaidLateForPeriod());
    }

    private static PostLoansResponse applyForLoanApplication(final Long clientId, final Integer loanProductId, final BigDecimal principal,
            final int loanTermFrequency, final int repaymentAfterEvery, final int numberOfRepayments, final int interestRate,
            final String expectedDisbursementDate, final String submittedOnDate, String transactionProcessorCode, String loanScheduleType,
            String loanScheduleProcessingType) {
        LOG.info("--------------------------------APPLYING FOR LOAN APPLICATION--------------------------------");
        return loanTransactionHelper.applyLoan(new PostLoansRequest().clientId(clientId).productId(loanProductId.longValue())
                .expectedDisbursementDate(expectedDisbursementDate).dateFormat(DATETIME_PATTERN)
                .transactionProcessingStrategyCode(transactionProcessorCode).locale("en").submittedOnDate(submittedOnDate)
                .amortizationType(1).interestRatePerPeriod(interestRate).interestCalculationPeriodType(1).interestType(0)
                .repaymentFrequencyType(0).repaymentEvery(repaymentAfterEvery).repaymentFrequencyType(0)
                .numberOfRepayments(numberOfRepayments).loanTermFrequency(loanTermFrequency).loanTermFrequencyType(0).principal(principal)
                .loanType("individual").loanScheduleProcessingType(loanScheduleProcessingType).loanScheduleType(loanScheduleType)
                .maxOutstandingLoanBalance(BigDecimal.valueOf(35000)));
    }

    private static PostLoansResponse applyForLoanApplication(final Long clientId, final Integer loanProductId, final BigDecimal principal,
            final int loanTermFrequency, final int repaymentAfterEvery, final int numberOfRepayments, final int interestRate,
            final String expectedDisbursementDate, final String submittedOnDate) {
        LOG.info("--------------------------------APPLYING FOR LOAN APPLICATION--------------------------------");
        return applyForLoanApplication(clientId, loanProductId, principal, loanTermFrequency, repaymentAfterEvery, numberOfRepayments,
                interestRate, expectedDisbursementDate, submittedOnDate,
                AdvancedPaymentScheduleTransactionProcessor.ADVANCED_PAYMENT_ALLOCATION_STRATEGY, LoanScheduleType.PROGRESSIVE.name(),
                LoanScheduleProcessingType.HORIZONTAL.name());
    }

    private static void validateLoanTransaction(GetLoansLoanIdResponse loanDetails, int index, double transactionAmount,
            double principalPortion, double overPaidPortion, double loanBalance) {
        assertEquals(transactionAmount, loanDetails.getTransactions().get(index).getAmount());
        assertEquals(principalPortion, loanDetails.getTransactions().get(index).getPrincipalPortion());
        assertEquals(overPaidPortion, loanDetails.getTransactions().get(index).getOverpaymentPortion());
        assertEquals(loanBalance, loanDetails.getTransactions().get(index).getOutstandingLoanBalance());
    }
}
