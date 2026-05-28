package Problems.S00_General.P02_OddEvenPrinter.Test;

import Problems.S00_General.P02_OddEvenPrinter.KThreadPrinter;

public class KThreadPrinterTest {
    public static void main(String[] args) {
        try {
            new KThreadPrinter(22, 5).solve();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
}
