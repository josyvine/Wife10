package com.tradeanalyst.app;

import java.util.List;

/**
 * HELPER CLASS: PatternPromptBuilder
 * Compiles raw chronological candlestick intervals and symbol parameters into a clean, structured prompt context for the AI.
 */
public class PatternPromptBuilder {

    /**
     * Serializes symbol metadata, timeframe intervals, and chronological candlestick listings into a structured prompt text block.
     * Now includes Volume records (OHLCV) to support native volumetric context.
     *
     * @param symbol The active cryptocurrency token pair symbol (e.g., BTC/USDT).
     * @param interval The current timeframe interval (e.g., 1h, 15m, 1D).
     * @param candles The lookback-bound slice of candles to be processed.
     * @return Formatted context prompt string.
     */
    public static String buildPrompt(String symbol, String interval, List<Candlestick> candles) {
        if (candles == null || candles.isEmpty()) {
            return "No candlestick dataset currently available for prompt compile.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== SYSTEM TRADING PARAMETERS ===\n");
        sb.append("Asset Pair: ").append(symbol).append("\n");
        sb.append("Timeframe Interval: ").append(interval).append("\n");
        sb.append("Lookback Dataset Span: ").append(candles.size()).append(" candlesticks\n\n");

        sb.append("=== CHRONOLOGICAL OHLC CANDLE SERIES ===\n");
        sb.append("Indices are formatted from oldest (Index 0) to newest/current live market (Index ")
          .append(candles.size() - 1).append("):\n");

        for (int i = 0; i < candles.size(); i++) {
            Candlestick c = candles.get(i);
            sb.append(String.format("Index: %d | Open: %.2f | High: %.2f | Low: %.2f | Close: %.2f | Volume: %.2f | Timestamp: %d\n",
                i, c.open, c.high, c.low, c.close, c.volume, c.timestamp));
        }

        sb.append("\n=== ANALYSIS AND EXECUTION INSTRUCTIONS ===\n");
        sb.append("Mathematically analyze the OHLC structural history to detect any active patterns (e.g., Double Bottoms, Head and Shoulders).\n");
        sb.append("If a pattern exists, identify the key swing point indices, evaluate the structural breakthrough, and calculate stop-loss and target prices.\n");
        sb.append("You must populate the 'timestamp' field for each coordinate Point inside the 'points' array by matching the indices directly to the provided candlestick list timestamps. This prevents drawing drift.\n");
        sb.append("You MUST return your structured analysis prefixed exactly with 'PATTERN: ' followed by the raw JSON, without markdown code block wrappers (do not use ```json or ```).");

        return sb.toString();
    }

    /**
     * Serializes a mathematically confirmed local pattern candidate, indicators, and breakout status
     * into a strict validation-only prompt. This enforces Phase 7 architecture rules.
     *
     * @param symbol The asset pair (e.g., BTC/USDT).
     * @param interval The active interval timeframe.
     * @param candles The lookback candle slice.
     * @param patternType The mathematically identified pattern type.
     * @param patternGeometry Structural JSON defining mapped swing points and boundaries.
     * @param trend Contextual trend orientation.
     * @param indicators Key values of indicators (RSI, EMAs, SMA, BB bands).
     * @param volumeStatus Volumetric confirmation rating.
     * @param breakoutStatus Breakout candle confirmation details.
     * @param mathConfidence Mathematically calculated confidence score (0-100).
     * @return Strict context validation prompt.
     */
    public static String buildValidationPrompt(String symbol, String interval, List<Candlestick> candles, 
                                               String patternType, String patternGeometry, String trend, 
                                               String indicators, String volumeStatus, String breakoutStatus, 
                                               double mathConfidence) {
        if (candles == null || candles.isEmpty()) {
            return "No candlestick dataset currently available for validation compile.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== SYSTEM TRADING METADATA ===\n");
        sb.append("Asset Pair: ").append(symbol).append("\n");
        sb.append("Timeframe Interval: ").append(interval).append("\n");
        sb.append("Market Price Stream: ").append(candles.get(candles.size() - 1).close).append("\n\n");

        sb.append("=== DETECTED PATTERN CANDIDATE (MATHEMATICALLY CONFIRMED) ===\n");
        sb.append("Pattern Type: ").append(patternType).append("\n");
        sb.append("Mathematically Calculated Confidence Score: ").append(String.format("%.1f%%", mathConfidence)).append("\n");
        sb.append("Trend Context: ").append(trend).append("\n");
        sb.append("Volume Confirmation: ").append(volumeStatus).append("\n");
        sb.append("Breakout Status: ").append(breakoutStatus).append("\n");
        sb.append("Indicators Setup: ").append(indicators).append("\n");
        sb.append("Pattern Geometry Coordinates: ").append(patternGeometry).append("\n\n");

        sb.append("=== CHRONOLOGICAL MARKET SERIES (OHLCV) ===\n");
        for (int i = 0; i < candles.size(); i++) {
            Candlestick c = candles.get(i);
            sb.append(String.format("Index: %d | Open: %.2f | High: %.2f | Low: %.2f | Close: %.2f | Volume: %.2f | Timestamp: %d\n",
                i, c.open, c.high, c.low, c.close, c.volume, c.timestamp));
        }

        sb.append("\n=== STRICT VALIDATION & EXPLANATION INSTRUCTIONS ===\n");
        sb.append("You are acting strictly as an Advisory Validator. However, if the local pattern geometry coordinates are empty '{}', you must perform a cognitive fallback scan over the raw candlestick list to identify any visual pattern setups.\n");
        sb.append("Analyze the provided local mathematical pattern candidate, verify its technical symmetry using the OHLCV data, and evaluate volume/indicator alignment.\n");
        sb.append("You must populate the 'timestamp' field for each coordinate Point inside your 'points' array by matching the indices directly to the provided candlestick list timestamps. This prevents drawing drift.\n");
        sb.append("Return your final validated assessment as a JSON payload prefixed exactly with 'PATTERN: '.\n");
        sb.append("The JSON schema MUST conform to the updated ChartPatternResponse structure, containing: \n");
        sb.append("1. An updated copy of the 'patterns' array incorporating any micro-adjustments to the validation explanation and fully populated 'timestamp' fields.\n");
        sb.append("2. An updated 'summary' with a validation status (CONFIRMED, RETESTING, or INVALIDATED), reasons, explanation, and an optional AI-driven minor confidence adjustment (maximum +-5%).\n");
        sb.append("Ensure no markdown wrappers like ```json or ``` are used.");

        return sb.toString();
    }
}