package bb2

import org.junit.Test

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class bb2Test {

    @Test
    fun testBB2() {
        val bb = bb2Main()

        bb.main()
    }

    @Test
    fun testBB2SingleTimepoint() {
        val bb = bb2Main()

        bb.main(single = false, max = 1)
    }

    @Test
    fun testBB2SingleChannelSingleTimepoint() {
        val bb = bb2Main()

        bb.main(single = true, max = 1)
    }
}