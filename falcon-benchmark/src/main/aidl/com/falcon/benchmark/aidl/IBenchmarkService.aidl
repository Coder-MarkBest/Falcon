package com.falcon.benchmark.aidl;

interface IBenchmarkService {
    String echoString(in String input);
    byte[] echoBytes(in byte[] input);
    long computeSum(in int from, in int to);
}
