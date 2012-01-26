/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package de.gaalop.tba.table.BitIO;

import java.io.IOException;
import java.util.LinkedList;

/**
 *
 * @author christian
 */
public class BitReader extends AbsBitReader {

    private long cache = 0;
    private int cachedBits = 0;

    @Override
    public int read(int bitCount) throws IOException {

        while (cachedBits < bitCount) {
            int data = (int) in.readChar();
            cache <<= 16;
            cache |= data;
            cachedBits += 16;
        }

        cachedBits -= bitCount;
        int result = (int) (cache >> cachedBits);
        
        for (int i=0;i<bitCount;i++)
            cache &= ~(1 << (i+cachedBits));

        return result;
    }

}
