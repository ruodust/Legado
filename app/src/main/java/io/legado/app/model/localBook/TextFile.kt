package io.legado.app.model.localBook

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultData
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.Utf8BomUtils
import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.min

class TextFile(private val book: Book) {

    companion object {
        private val padRegex = "^[\\n\\s]+".toRegex()
        private const val bufferSize = 8 * 1024 * 1024
        var txtBuffer: ByteArray? = null
        var bufferStart = -1
        var bufferEnd = -1
        var bookUrl = ""

        @Throws(FileNotFoundException::class)
        fun getChapterList(book: Book): ArrayList<BookChapter> {
            return TextFile(book).getChapterList()
        }

        @Synchronized
        @Throws(FileNotFoundException::class)
        fun getContent(book: Book, bookChapter: BookChapter): String {
            if (txtBuffer == null
                || bookUrl != book.bookUrl
                || bookChapter.start!! > bufferEnd
                || bookChapter.end!! < bufferStart
            ) {
                bookUrl = book.bookUrl
                LocalBook.getBookInputStream(book).use { bis ->
                    bufferStart = bufferSize * (bookChapter.start!! / bufferSize).toInt()
                    txtBuffer = ByteArray(min(bufferSize, bis.available() - bufferStart))
                    bufferEnd = bufferStart + txtBuffer!!.size
                    bis.skip(bufferStart.toLong())
                    bis.read(txtBuffer)
                }
            }

            val count = (bookChapter.end!! - bookChapter.start!!).toInt()
            val buffer = ByteArray(count)

            if (bookChapter.start!! < bufferEnd && bookChapter.end!! > bufferEnd
                || bookChapter.start!! < bufferStart && bookChapter.end!! > bufferStart
            ) {
                /** ????????????????????????????????? */
                LocalBook.getBookInputStream(book).use { bis ->
                    bis.skip(bookChapter.start!!)
                    bis.read(buffer)
                }
            } else {
                /** ??????????????????????????? */
                txtBuffer!!.copyInto(
                    buffer,
                    0,
                    (bookChapter.start!! - bufferStart).toInt(),
                    (bookChapter.end!! - bufferStart).toInt()
                )
            }

            return String(buffer, book.fileCharset())
                .substringAfter(bookChapter.title)
                .replace(padRegex, "??????")
        }

    }

    private val blank: Byte = 0x0a

    //???????????????????????????????????????
    private val bufferSize = 512000

    //???????????????????????????????????????????????????
    private val maxLengthWithNoToc = 10 * 1024

    //????????????????????????????????????????????????????????????
    private val maxLengthWithToc = 102400

    private var charset: Charset = book.fileCharset()

    /**
     * ????????????
     */
    @Throws(FileNotFoundException::class)
    fun getChapterList(): ArrayList<BookChapter> {
        if (book.charset == null || book.tocUrl.isBlank()) {
            LocalBook.getBookInputStream(book).use { bis ->
                val buffer = ByteArray(bufferSize)
                val length = bis.read(buffer)
                if (book.charset.isNullOrBlank()) {
                    book.charset = EncodingDetect.getEncode(buffer.copyOf(length))
                }
                charset = book.fileCharset()
                if (book.tocUrl.isBlank()) {
                    val blockContent = String(buffer, 0, length, charset)
                    book.tocUrl = getTocRule(blockContent)?.pattern() ?: ""
                }
            }
        }
        val toc = analyze(book.tocUrl.toPattern(Pattern.MULTILINE))
        toc.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            bookChapter.bookUrl = book.bookUrl
            bookChapter.url = MD5Utils.md5Encode16(book.originName + index + bookChapter.title)
        }
        return toc
    }

    /**
     * ?????????????????????
     */
    private fun analyze(pattern: Pattern?): ArrayList<BookChapter> {
        if (pattern?.pattern().isNullOrEmpty()) {
            return analyze()
        }
        pattern ?: return analyze()
        val toc = arrayListOf<BookChapter>()
        LocalBook.getBookInputStream(book).use { bis ->
            var blockContent: String
            //????????????
            var curOffset: Long = 0
            //???????????????
            var length: Int
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            bis.read(buffer, 0, 3)
            if (Utf8BomUtils.hasBom(buffer)) {
                bufferStart = 0
                curOffset = 3
            }
            //???????????????????????????buffer???????????????????????????
            while (
                bis.read(
                    buffer,
                    bufferStart,
                    bufferSize - bufferStart
                ).also { length = it } > 0
            ) {
                var end = bufferStart + length
                if (end == bufferSize) {
                    for (i in bufferStart + length - 1 downTo 0) {
                        if (buffer[i] == blank) {
                            end = i
                            break
                        }
                    }
                }
                //??????????????????String, ????????????length
                blockContent = String(buffer, 0, end, charset)
                buffer.copyInto(buffer, 0, end, bufferStart + length)
                bufferStart = bufferStart + length - end
                length = end
                //??????Block????????????String?????????
                var seekPos = 0
                //??????????????????
                val matcher: Matcher = pattern.matcher(blockContent)
                //????????????????????????
                while (matcher.find()) { //??????????????????????????????????????????????????????
                    val chapterStart = matcher.start()
                    //??????????????????
                    val chapterContent = blockContent.substring(seekPos, chapterStart)
                    val chapterLength = chapterContent.toByteArray(charset).size
                    val lastStart = toc.lastOrNull()?.start ?: curOffset
                    if (book.getSplitLongChapter()
                        && curOffset + chapterLength - lastStart > maxLengthWithToc
                    ) {
                        toc.lastOrNull()?.let {
                            it.end = it.start
                        }
                        //??????????????????????????????
                        val lastTitle = toc.lastOrNull()?.title
                        val lastTitleLength = lastTitle?.toByteArray(charset)?.size ?: 0
                        val chapters = analyze(
                            lastStart + lastTitleLength,
                            curOffset + chapterLength
                        )
                        lastTitle?.let {
                            chapters.forEachIndexed { index, bookChapter ->
                                bookChapter.title = "$lastTitle(${index + 1})"
                            }
                        }
                        toc.addAll(chapters)
                        //??????????????????
                        val curChapter = BookChapter()
                        curChapter.title = matcher.group()
                        curChapter.start = curOffset + chapterLength
                        toc.add(curChapter)
                    } else if (seekPos == 0 && chapterStart != 0) {
                        /*
                         * ?????? seekPos == 0 && chapterStart != 0 ????????????block????????????????????????
                         * ?????????????????????????????? ??????????????????????????????????????????
                         */
                        if (toc.isEmpty()) { //?????????????????????????????????????????????
                            //????????????
                            if (StringUtils.trim(chapterContent).isNotEmpty()) {
                                val qyChapter = BookChapter()
                                qyChapter.title = "??????"
                                qyChapter.start = curOffset
                                qyChapter.end = curOffset + chapterLength.toLong()
                                toc.add(qyChapter)
                            }
                            //??????????????????
                            val curChapter = BookChapter()
                            curChapter.title = matcher.group()
                            curChapter.start = curOffset + chapterLength.toLong()
                            toc.add(curChapter)
                        } else { //?????????block?????????????????????????????????????????????
                            //??????????????????
                            val lastChapter = toc.last()
                            lastChapter.isVolume =
                                chapterContent.substringAfter(lastChapter.title).isBlank()
                            //?????????????????????????????????
                            lastChapter.end =
                                lastChapter.end!! + chapterLength.toLong()
                            //??????????????????
                            val curChapter = BookChapter()
                            curChapter.title = matcher.group()
                            curChapter.start = lastChapter.end
                            toc.add(curChapter)
                        }
                    } else {
                        if (toc.isNotEmpty()) { //??????????????????
                            //??????????????????
                            val lastChapter = toc.last()
                            lastChapter.isVolume =
                                chapterContent.substringAfter(lastChapter.title).isBlank()
                            lastChapter.end =
                                lastChapter.start!! + chapterContent.toByteArray(charset).size.toLong()
                            //??????????????????
                            val curChapter = BookChapter()
                            curChapter.title = matcher.group()
                            curChapter.start = lastChapter.end
                            toc.add(curChapter)
                        } else { //????????????????????????????????????
                            val curChapter = BookChapter()
                            curChapter.title = matcher.group()
                            curChapter.start = curOffset
                            curChapter.end = curOffset
                            toc.add(curChapter)
                        }
                    }
                    //??????????????????
                    seekPos += chapterContent.length
                }
                //block????????????
                curOffset += length.toLong()
                //????????????????????????
                toc.lastOrNull()?.end = curOffset
            }
        }
        System.gc()
        System.runFinalization()
        return toc
    }

    /**
     * ?????????????????????
     */
    private fun analyze(
        fileStart: Long = 0L,
        fileEnd: Long = Long.MAX_VALUE
    ): ArrayList<BookChapter> {
        val toc = arrayListOf<BookChapter>()
        LocalBook.getBookInputStream(book).use { bis ->
            //block?????????
            var blockPos = 0
            //????????????
            var curOffset: Long = 0
            var chapterPos = 0
            //???????????????
            var length = 0
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            if (fileStart == 0L) {
                bis.read(buffer, 0, 3)
                if (Utf8BomUtils.hasBom(buffer)) {
                    bufferStart = 0
                    curOffset = 3
                }
            } else {
                bis.skip(fileStart)
                curOffset = fileStart
                bufferStart = 0
            }
            //???????????????????????????buffer???????????????????????????
            while (
                fileEnd - curOffset - bufferStart > 0 &&
                bis.read(
                    buffer,
                    bufferStart,
                    min(
                        (bufferSize - bufferStart).toLong(),
                        fileEnd - curOffset - bufferStart
                    ).toInt()
                ).also { length = it } > 0
            ) {
                blockPos++
                //?????????buffer????????????
                var chapterOffset = 0
                //??????????????????????????????
                length += bufferStart
                var strLength = length
                //???????????????
                chapterPos = 0
                while (strLength > 0) {
                    chapterPos++
                    //????????????????????????
                    if (strLength > maxLengthWithNoToc) { //???buffer?????????????????????
                        var end = length
                        //??????????????????????????????
                        for (i in chapterOffset + maxLengthWithNoToc until length) {
                            if (buffer[i] == blank) {
                                end = i
                                break
                            }
                        }
                        val chapter = BookChapter()
                        chapter.title = "???${blockPos}???($chapterPos)"
                        chapter.start = toc.lastOrNull()?.end ?: curOffset
                        chapter.end = chapter.start!! + end - chapterOffset
                        toc.add(chapter)
                        //??????????????????????????????
                        strLength -= (end - chapterOffset)
                        //?????????????????????
                        chapterOffset = end
                    } else {
                        buffer.copyInto(buffer, 0, length - strLength, length)
                        length -= strLength
                        bufferStart = strLength
                        strLength = 0
                    }
                }
                //block????????????
                curOffset += length.toLong()
            }
            //??????????????????
            if (bufferStart > 100 || toc.isEmpty()) {
                val chapter = BookChapter()
                chapter.title = "???${blockPos}???(${chapterPos})"
                chapter.start = toc.lastOrNull()?.end ?: curOffset
                chapter.end = chapter.start!! + bufferStart
                toc.add(chapter)
            } else {
                toc.lastOrNull()?.let {
                    it.end = it.end!! + bufferStart
                }
            }
        }
        return toc
    }

    /**
     * ??????????????????????????????1???????????????
     */
    private fun getTocRule(content: String): Pattern? {
        val rules = getTocRules().reversed()
        var maxCs = 1
        var tocPattern: Pattern? = null
        for (tocRule in rules) {
            val pattern = tocRule.rule.toPattern(Pattern.MULTILINE)
            val matcher = pattern.matcher(content)
            var cs = 0
            while (matcher.find()) {
                cs++
            }
            if (cs >= maxCs) {
                maxCs = cs
                tocPattern = pattern
            }
        }
        return tocPattern
    }

    /**
     * ???????????????????????????
     */
    private fun getTocRules(): List<TxtTocRule> {
        var rules = appDb.txtTocRuleDao.enabled
        if (rules.isEmpty()) {
            rules = DefaultData.txtTocRules.apply {
                appDb.txtTocRuleDao.insert(*this.toTypedArray())
            }.filter {
                it.enable
            }
        }
        return rules
    }

}