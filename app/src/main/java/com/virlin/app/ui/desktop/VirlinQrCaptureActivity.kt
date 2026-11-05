package com.virlin.app.ui.desktop

import com.journeyapps.barcodescanner.CaptureActivity

/** Overrides ZXing's landscape-only activity so scanning follows user rotation. */
class VirlinQrCaptureActivity : CaptureActivity()
