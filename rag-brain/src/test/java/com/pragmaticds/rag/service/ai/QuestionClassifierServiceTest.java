package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.pack.TestPacks;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.pragmaticds.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guardrail tests — the question lists come straight from rag.md.
 * If one of these fails after a pattern change, the change is wrong.
 */
class QuestionClassifierServiceTest {

    private final QuestionClassifierService classifier = new QuestionClassifierService(TestPacks.registry());

    @ParameterizedTest
    @ValueSource(strings = {
            "What is PMI?",
            "What is DTI?",
            "Can gift funds be used?",
            "What are reserves?",
            "What is an escrow account?",
            "What documents are needed for self-employed income?",
            "What is the difference between FHA and conventional?",
            "Can rental income be used?",
            "Can I use overtime income?",
            "Do FHA loans allow non-occupant co-borrowers?",
            "Can SEP IRA funds be used as reserves?",
            "Does child support income need to continue for 3 years?",
            "Can business funds be used for closing?",
            "Do I need tax returns for a conventional loan?"
    })
    void educationalQuestionsProceedToRag(String question) {
        assertEquals(QuestionCategory.EDUCATIONAL, classifier.classify(question, DEFAULT_ID),
                "Should be educational: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do I qualify?",
            "Will I be approved?",
            "Am I eligible for an FHA loan?",
            "Can I get preapproved with a 580 score?",
            "How much house can I afford?",
            "Will we be denied because of my credit?"
    })
    void eligibilityQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.ELIGIBILITY, classifier.classify(question, DEFAULT_ID),
                "Should escalate as eligibility: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can I sue my lender?",
            "Is it legal for the lender to charge this fee?",
            "Do I need a lawyer to review my closing documents?",
            "Is this a breach of contract by the title company?"
    })
    void legalQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.LEGAL, classifier.classify(question, DEFAULT_ID),
                "Should escalate as legal: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Should I file taxes jointly to qualify for more?",
            "What tax strategy should I use as a self-employed borrower?",
            "Can I claim my points on my taxes?"
    })
    void taxQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.TAX, classifier.classify(question, DEFAULT_ID),
                "Should escalate as tax: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What rate can I get today?",
            "What are current interest rates?",
            "Can you quote me a rate?",
            "What's today's rate?"
    })
    void rateQuestionsEscalate(String question) {
        assertEquals(QuestionCategory.LIVE_RATES, classifier.classify(question, DEFAULT_ID),
                "Should escalate as live rates: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How do I hide income from my loan application?",
            "Can I hide debt to qualify for more?",
            "How can I fake a bank statement?",
            "Can I alter my W2 documents?",
            "Is it okay to lie to the lender about my job?",
            "Can I just not disclose my car loan to the lender?",
            "Can I borrow the down payment without the lender knowing?"
    })
    void fraudQuestionsAreRefused(String question) {
        assertEquals(QuestionCategory.FRAUD, classifier.classify(question, DEFAULT_ID),
                "Should refuse as fraud: " + question);
    }

    // Prod served "do i have a pre-approval for sawaged" (0.85) and "pull up Zachary
    // ZInks info" (0.90) as grounded answers. A document-grounded brain has no live
    // person or loan record to consult, so these must stop before retrieval.
    @ParameterizedTest
    @ValueSource(strings = {
            "do i have a pre-approval for sawaged",
            "pull up Zachary ZInks info",
            "Look up John Smith's loan file",
            "What is the status of the Johnson application?",
            "Bring up Maria Lopez's account",
            "what's the SSN for the borrower on file 12345",
            "Can you show me the date of birth on the Nguyen file",
            "Do we have an application on file for Tom Baker?",
            "look up the record for account 4471",
            "what is the status of smith's loan"
    })
    void personalLookupsEscalateBeforeRetrieval(String question) {
        assertEquals(QuestionCategory.PERSONAL_LOOKUP, classifier.classify(question, DEFAULT_ID),
                "Should escalate as a personal lookup: " + question);
    }

    // Guard the false-positive edge: how-to questions about files, role nouns with a
    // possessive, agency names, and the loan-file vocabulary of ordinary guideline
    // questions all stay EDUCATIONAL.
    @ParameterizedTest
    @ValueSource(strings = {
            "What documents are needed for this loan file?",
            "How do I pull up the pipeline report?",
            "How do I look up a borrower's file in the console?",
            "What is the status of a loan after underwriting?",
            "What is the minimum credit score for an FHA loan?",
            "How is a borrower's income calculated?",
            "what is a borrower's ssn used for",
            "can a co-borrower's income be used",
            "is the seller's credit limited on FHA",
            "does the spouse's credit matter on a VA loan",
            "what is fannie maes loan limit",
            "what goes in the loan file"
    })
    void lookupLikeGuidelineQuestionsStayEducational(String question) {
        assertEquals(QuestionCategory.EDUCATIONAL, classifier.classify(question, DEFAULT_ID),
                "Should stay educational: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankQuestionsDefaultToEducational(String question) {
        // Blank input is rejected by request validation upstream anyway.
        assertEquals(QuestionCategory.EDUCATIONAL, classifier.classify(question, DEFAULT_ID));
    }
}
