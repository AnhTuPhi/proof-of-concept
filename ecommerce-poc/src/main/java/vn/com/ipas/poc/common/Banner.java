package vn.com.ipas.poc.common;

public final class Banner {
    private Banner() {}

    public static void section(String title) {
        String bar = "=".repeat(72);
        System.out.println();
        System.out.println(bar);
        System.out.println("  " + title);
        System.out.println(bar);
    }

    public static void sub(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
    }
}
