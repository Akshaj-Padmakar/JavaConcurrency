package Problems.S00_General.P02_OddEvenPrinter.Test;

import Problems.S00_General.P02_OddEvenPrinter.OddEvenPrinter;

public class OddEvenPrinterTest {
    public static void main(String[] args) {
        try {
            new OddEvenPrinter(16).solve();
        } catch (InterruptedException ex) {
            System.out.println("OddEven Printer failed !");
            ex.printStackTrace();
        }
    }
}
