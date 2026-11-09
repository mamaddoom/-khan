package com.tobacco.pos.ml

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class VisualAnalysisResult(
    val detectedBarcode: String? = null,
    val recognizedBrandOrName: String? = null,
    val extractedConsumerPrice: Long? = null,
    val rawText: String? = null
)

class TobaccoScannerAnalyzer(
    private val enableVisualOcr: Boolean = true,
    private val onResult: (VisualAnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    // پشتیبانی کامل از بارکدهای خطی و دوبعدی دخانیات (EAN-13, DataMatrix)
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_UPC_A
            )
            .build()
    )

    // موتور OCR زنده روی گوشی
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastScannedCode: String? = null
    private var lastScanTimestamp: Long = 0L
    private val debounceDelayMs = 800L // جلوگیری از اسکن پشت سر هم یک پاکت

    // لیست برندهای متداول دخانیات جهت تطبیق هوشمند متنی
    private val knownBrands = listOf(
        "Bahman" to "بهمن",
        "Winston" to "وینستون",
        "Kent" to "کنت",
        "Marlboro" to "مارلبرو",
        "Esse" to "اسه",
        "Magna" to "مگنا",
        "Montana" to "مونتانا",
        "Al Sheikh" to "الشیخ",
        "Al Fakher" to "الفاخر",
        "Mazaya" to "مزایا",
        "Gandom" to "گندم",
        "Clipper" to "فندک کلیپر",
        "Bic" to "فندک بیک"
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        // ۱. ابتدا تلاش برای شناسایی بارکد استاندارد
        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val primaryBarcode = barcodes.firstOrNull()?.rawValue
                val currentTime = System.currentTimeMillis()

                if (primaryBarcode != null) {
                    if (primaryBarcode != lastScannedCode || (currentTime - lastScanTimestamp) > debounceDelayMs) {
                        lastScannedCode = primaryBarcode
                        lastScanTimestamp = currentTime
                        onResult(VisualAnalysisResult(detectedBarcode = primaryBarcode))
                    }
                } else if (enableVisualOcr) {
                    // ۲. اگر بارکد دیده نشد یا مخدوش بود، آنالیز متنی و OCR جعبه اجرا می‌شود
                    analyzePackText(inputImage)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun analyzePackText(inputImage: InputImage) {
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                if (fullText.isNotBlank()) {
                    var identifiedBrand: String? = null
                    for ((brandEn, brandFa) in knownBrands) {
                        if (fullText.contains(brandEn, ignoreCase = true) || fullText.contains(brandFa)) {
                            identifiedBrand = "$brandFa ($brandEn)"
                            break
                        }
                    }

                    // استخراج قیمت چاپ شده روی پاکت (مثلا: "قیمت مصرف کننده: ۳۵۰,۰۰۰ ریال")
                    val extractedPrice = extractPriceFromText(fullText)

                    if (identifiedBrand != null || extractedPrice != null) {
                        val currentTime = System.currentTimeMillis()
                        if ((currentTime - lastScanTimestamp) > debounceDelayMs) {
                            lastScanTimestamp = currentTime
                            onResult(
                                VisualAnalysisResult(
                                    recognizedBrandOrName = identifiedBrand,
                                    extractedConsumerPrice = extractedPrice,
                                    rawText = fullText
                                )
                            )
                        }
                    }
                }
            }
    }

    /**
     * استخراج رقم ریال/تومان چاپ‌شده روی بسته‌بندی دخانیات
     */
    private fun extractPriceFromText(text: String): Long? {
        // الگوهای رایج درج قیمت روی پاکت در ایران
        val regex = Regex("""(?i)(?:قیمت|مصرف\s*کننده|Rls|ریال|تومان)?[:\s]*([0-9,]+)\s*(?:ریال|تومان|Rls)?""")
        val match = regex.find(text)
        if (match != null) {
            val rawDigits = match.groupValues[1].replace(",", "").trim()
            val parsed = rawDigits.toLongOrNull() ?: return null
            // اگر قیمت به ریال باشد (معمولاً بالای ۱۰۰ هزار ریال برای پاکت سیگار)، تبدیل به تومان می‌شود
            return if (parsed > 50_000 && (text.contains("ریال") || text.contains("Rls", ignoreCase = true))) {
                parsed / 10
            } else {
                parsed
            }
        }
        return null
    }
}
