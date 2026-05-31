package Problems.S02_LessClassical.P03_HilzerBarbershop.Test;

import Problems.S02_LessClassical.P03_HilzerBarbershop.HilzerBarbershop;

public class HilzerBarbershopTest {

    public static void main(String[] args) throws InterruptedException {

        /*
         * Test 1:
         * Basic Hilzer flow
         *
         * Validates:
         * - standing area
         * - sofa waiting
         * - multi-barber servicing
         * - haircut protocol
         * - payment protocol
         */
        System.out.println(
                "\n========== TEST-1 : Basic Hilzer ==========\n");

        new HilzerBarbershop(10).solve();

        /*
         * Test 2:
         * Shop capacity overflow
         *
         * maxCustomers = 20
         *
         * Expect:
         * some customers rejected.
         */
        System.out.println(
                "\n========== TEST-2 : Shop Full ==========\n");

        new HilzerBarbershop(25).solve();
    }
}