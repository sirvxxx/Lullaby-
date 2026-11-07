package me.sorihiko.dango.music

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP client untuk NewPipeExtractor, murni java.base (tanpa OkHttp). */
class NewPipeDownloaderImpl : Downloader() {

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }

    override fun execute(request: Request): Response {
        val c = URL(request.url()).openConnection() as HttpURLConnection
        c.requestMethod = request.httpMethod()
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)

        for ((name, values) in request.headers()) {
            values.forEachIndexed { i, v ->
                if (i == 0) c.setRequestProperty(name, v) else c.addRequestProperty(name, v)
            }
        }

        val body = request.dataToSend()
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body) }
        }

        val code = c.responseCode
        if (code == 429) {
            c.disconnect()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""

        val headers = HashMap<String, List<String>>()
        for ((k, v) in c.headerFields) if (k != null) headers[k] = v

        return Response(code, c.responseMessage, headers, text, c.url.toString())
    }
}
