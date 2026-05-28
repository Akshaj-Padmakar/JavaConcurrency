package Problems.S00_General.P02_OddEvenPrinter.Test;

import Problems.S00_General.P02_OddEvenPrinter.OddEvenPrinterSemaphore;

public class OddEvenPrinterSemaphoreTest {
    public static void main(String[] args) {
        try {
            new OddEvenPrinterSemaphore(14).solve();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
}
