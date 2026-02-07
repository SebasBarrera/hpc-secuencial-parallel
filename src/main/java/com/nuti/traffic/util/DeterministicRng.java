package com.nuti.traffic.util;

public final class DeterministicRng {

    private DeterministicRng() {
    }

    public static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    public static double unitDouble(long seed, int vehicleId, int tick, long salt) {
        long z = seed;
        z ^= ((long) vehicleId * 0x9e3779b97f4a7c15L);
        z ^= ((long) tick * 0xbf58476d1ce4e5b9L);
        z ^= salt;
        long mixed = mix64(z);
        long mantissa = (mixed >>> 11) & ((1L << 53) - 1);
        return mantissa / (double) (1L << 53);
    }
}
