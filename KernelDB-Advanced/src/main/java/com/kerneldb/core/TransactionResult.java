package com.kerneldb.core;

public record TransactionResult(String name, long sequence, long elapsedMillis, long lockWaitMillis) { }
