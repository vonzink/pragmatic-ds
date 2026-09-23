package com.pragmaticds.rag.service.analyze.calc;

/** Invalid/missing calculation input — caught by the dispatcher and turned into an ERROR result. */
class CalcInputException extends RuntimeException {
    CalcInputException(String message) {
        super(message);
    }
}
