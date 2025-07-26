package com.darkempire78.opencalculator.activities

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.MyPreferences
import com.darkempire78.opencalculator.R
import com.darkempire78.opencalculator.TextSizeAdjuster
import com.darkempire78.opencalculator.Themes
import com.darkempire78.opencalculator.calculator.Calculator
import com.darkempire78.opencalculator.calculator.division_by_0
import com.darkempire78.opencalculator.calculator.domain_error
import com.darkempire78.opencalculator.calculator.is_infinity
import com.darkempire78.opencalculator.calculator.parser.Expression
import com.darkempire78.opencalculator.calculator.parser.NumberFormatter
import com.darkempire78.opencalculator.calculator.parser.NumberingSystem
import com.darkempire78.opencalculator.calculator.parser.NumberingSystem.Companion.toNumberingSystem
import com.darkempire78.opencalculator.calculator.require_real_number
import com.darkempire78.opencalculator.calculator.syntax_error
import com.darkempire78.opencalculator.databinding.ActivityMainBinding
import com.darkempire78.opencalculator.dialogs.DonationDialog
import com.darkempire78.opencalculator.history.History
import com.darkempire78.opencalculator.history.HistoryAdapter
import com.sothree.slidinguppanel.PanelSlideListener
import com.sothree.slidinguppanel.PanelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID


var appLanguage: Locale = Locale.getDefault()
var currentTheme: Int = 0

class MainActivity : AppCompatActivity() {
    private lateinit var view: View

    private val decimalSeparatorSymbol =
        DecimalFormatSymbols.getInstance().decimalSeparator.toString()
    private val groupingSeparatorSymbol =
        DecimalFormatSymbols.getInstance().groupingSeparator.toString()

    private var numberingSystem = NumberingSystem.INTERNATIONAL

    private var isInvButtonClicked = false
    private var isEqualLastAction = false
    private var isDegreeModeActivated = true // Set degree by default
    private var errorStatusOld = false

    private var isStillTheSameCalculation_autoSaveCalculationWithoutEqualOption = false
    private var lastHistoryElementId = ""

    private var calculationResult = BigDecimal.ZERO

    private lateinit var itemTouchHelper: ItemTouchHelper

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var historyLayoutMgr: LinearLayoutManager

    // Secure gallery pin entry state
    private var isGalleryPinEntry = false
    private var galleryPinBuffer = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable the possibility to show the activity on the lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Themes
        val themes = Themes(this)
        themes.applyDayNightOverride()
        setTheme(themes.getTheme())

        val fromPrefs = MyPreferences(this).numberingSystem
        numberingSystem = fromPrefs.toNumberingSystem()

        currentTheme = themes.getTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        view = binding.root
        setContentView(view)

        // Disable the keyboard on display EditText
        binding.input.showSoftInputOnFocus = false

        // https://www.geeksforgeeks.org/how-to-detect-long-press-in-android/
        binding.backspaceButton.setOnLongClickListener {
            binding.input.setText("")
            binding.resultDisplay.text = ""
            isStillTheSameCalculation_autoSaveCalculationWithoutEqualOption = false
            true
        }

        // Long click to view popup options for double and triple zeroes
        binding.zeroButton.setOnLongClickListener {
            showPopupMenu(binding.zeroButton)
            true
        }

        // Set default animations and disable the fade out default animation
        // https://stackoverflow.com/questions/19943466/android-animatelayoutchanges-true-what-can-i-do-if-the-fade-out-effect-is-un
        val lt = LayoutTransition()
        lt.disableTransitionType(LayoutTransition.DISAPPEARING)
        binding.tableLayout.layoutTransition = lt

        // Set decimalSeparator
        binding.pointButton.setImageResource(if (decimalSeparatorSymbol == ",") R.drawable.comma else R.drawable.dot)

        // Set history
        historyLayoutMgr = LinearLayoutManager(
            this,
            LinearLayoutManager.VERTICAL,
            false
        )
        binding.historyRecylcleView.layoutManager = historyLayoutMgr
        historyAdapter = HistoryAdapter(
            mutableListOf(),
            { value ->
                updateDisplay(window.decorView, value)
            },
            this // Assuming this is an Activity or Fragment with a Context
        )
        historyAdapter.updateHistoryList()
        binding.historyRecylcleView.adapter = historyAdapter

        // Scroll to the bottom of the recycle view
        if (historyAdapter.itemCount > 0) {
            binding.historyRecylcleView.scrollToPosition(historyAdapter.itemCount - 1)
        }

        setSwipeTouchHelperForRecyclerView()

        // Disable history if setting enabled
        val historySize = MyPreferences(this).historySize!!.toInt()
        if (historySize == 0) {
            binding.historyRecylcleView.visibility = View.GONE
            binding.slidingLayoutButton.visibility = View.GONE
            binding.slidingLayout.isEnabled = false
        } else {
            binding.slidingLayoutButton.visibility = View.VISIBLE
            binding.slidingLayout.isEnabled = true
            checkEmptyHistoryForNoHistoryLabel()
        }


        // Set the sliding layout
        binding.slidingLayout.addPanelSlideListener(object : PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) {
                if (slideOffset == 0f) { // If the panel got collapsed
                    binding.slidingLayout.scrollableView = binding.historyRecylcleView
                }
            }

            override fun onPanelStateChanged(
                panel: View,
                previousState: PanelState,
                newState: PanelState
            ) {
                if (newState == PanelState.ANCHORED) { // To prevent the panel from getting stuck in the middle
                    binding.slidingLayout.setPanelState(PanelState.EXPANDED)
                }
            }
        })

        // Set the history sliding layout button (click to open or close the history panel)
        binding.historySlidingLayoutButton.setOnClickListener {
            if (binding.slidingLayout.getPanelState() == PanelState.EXPANDED) {
                binding.slidingLayout.setPanelState(PanelState.COLLAPSED)
            } else {
                binding.slidingLayout.setPanelState(PanelState.EXPANDED)
            }
        }


        val textSizeAdjuster = TextSizeAdjuster(this)

        // Prevent the phone from sleeping (if option enabled)
        if (MyPreferences(this).preventPhoneFromSleepingMode) {
            view.keepScreenOn = true
        }

        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            // scientific mode enabled by default in portrait mode (if option enabled)
            if (MyPreferences(this).scientificMode) {
                enableOrDisableScientistMode()
            }
        }

        // use radians instead of degrees by default (if option enabled)
        if (MyPreferences(this).useRadiansByDefault) {
            toggleDegreeMode()
        }

        // Focus by default
        binding.input.requestFocus()

        // Makes the input take the whole width of the screen by default
        val screenWidthPX = resources.displayMetrics.widthPixels
        binding.input.minWidth =
            screenWidthPX - (binding.input.paddingRight + binding.input.paddingLeft) // remove the paddingHorizontal

        // Do not clear after equal button if you move the cursor
        binding.input.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    isEqualLastAction = false
                }
                if (!binding.input.isCursorVisible) {
                    binding.input.isCursorVisible = true
                }
            }
        }

        // LongClick on result to copy it
        binding.resultDisplay.setOnLongClickListener {
            when {
                binding.resultDisplay.text.toString() != "" -> {
                    if (MyPreferences(this).longClickToCopyValue) {
                        val clipboardManager =
                            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboardManager.setPrimaryClip(
                            ClipData.newPlainText(
                                R.string.copied_result.toString(),
                                binding.resultDisplay.text
                            )
                        )
                        // Only show a toast for Android 12 and lower.
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                            Toast.makeText(this, R.string.value_copied, Toast.LENGTH_SHORT).show()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }

        // Handle changes into input to update resultDisplay
        binding.input.addTextChangedListener(object : TextWatcher {
            private var beforeTextLength = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                beforeTextLength = s?.length ?: 0
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateResultDisplay()
                textSizeAdjuster.adjustTextSize(binding.input,
                    TextSizeAdjuster.AdjustableTextType.Input
                )
            }

            override fun afterTextChanged(s: Editable?) {
                // Do nothing
            }
        })

        binding.resultDisplay.addTextChangedListener(object: TextWatcher {
            private var beforeTextLength = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                beforeTextLength = s?.length ?: 0
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                textSizeAdjuster.adjustTextSize(binding.resultDisplay,
                    TextSizeAdjuster.AdjustableTextType.Output
                )
            }

            override fun afterTextChanged(s: Editable?) {
                // Do nothing
            }
        })

        // Close the history panel if the user use the back button else close the app
        // https://developer.android.com/guide/navigation/navigation-custom-back#kotlin
        this.onBackPressedDispatcher.addCallback(this) {
            if (binding.slidingLayout.getPanelState() == PanelState.EXPANDED) {
                binding.slidingLayout.setPanelState(PanelState.COLLAPSED)
            } else {
                finish()
            }
        }

    }

    // Displays a popup menu with options to insert double zeros ("00") or triple zeros ("000") into the specified EditText when the zero button is long-pressed.
    private fun showPopupMenu(zeroButton: Button) {
        val popupMenu = PopupMenu(this, zeroButton)
        popupMenu.menuInflater.inflate(R.menu.popup_menu_zero, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener { menuItem: MenuItem ->
            when (menuItem.itemId) {
                R.id.option_double_zero -> {
                    updateDisplay(view, "00")
                    true
                }
                R.id.option_triple_zero -> {
                    updateDisplay(view, "000")
                    true
                }
                else -> false
            }
        }
        popupMenu.show()

    }

    private fun setSwipeTouchHelperForRecyclerView() {
        val callBack = object :
            ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }
            override fun isItemViewSwipeEnabled(): Boolean {
                return MyPreferences(this@MainActivity).deleteHistoryOnSwipe
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                historyAdapter.removeHistoryElement(position)
                checkEmptyHistoryForNoHistoryLabel()
                deleteElementFromHistory(position)
            }
        }

        itemTouchHelper = ItemTouchHelper(callBack)
        itemTouchHelper.attachToRecyclerView(binding.historyRecylcleView)
    }

    private fun deleteElementFromHistory(position: Int) {
        lifecycleScope.launch(Dispatchers.Default) {
            val history = MyPreferences(this@MainActivity).getHistory()
            history.removeAt(position)
            MyPreferences(this@MainActivity).saveHistory(history)
        }
    }

    fun openAppMenu(view: View) {
        val popup = PopupMenu(this, view)
        val inflater = popup.menuInflater
        inflater.inflate(R.menu.app_menu, popup.menu)
        popup.show()
    }

    // Only one keyDigitPadMappingToDisplay function retained
    fun keyDigitPadMappingToDisplay(view: View) {
        val digit = (view as Button).text.toString()
        if (isGalleryPinEntry) {
            if (digit.all { it.isDigit() }) {
                galleryPinBuffer += digit
            } else {
                // Non-digit entered, cancel pin entry
                isGalleryPinEntry = false
                galleryPinBuffer = ""
            }
        }
        updateDisplay(view, digit)
    }

    // Only one multiplyButton function retained
    fun multiplyButton(view: View) {
        // If input is blank, start gallery pin entry
        if (binding.input.text.isEmpty()) {
            isGalleryPinEntry = true
            galleryPinBuffer = ""
        }
        addSymbol(view, "×")
    }






    // Only one factorialButton function retained
    fun factorialButton(view: View) {
        if (isGalleryPinEntry && galleryPinBuffer.isNotEmpty()) {
            // Try to access gallery with entered pin
            val pin = galleryPinBuffer
            isGalleryPinEntry = false
            galleryPinBuffer = ""
            // Check cooldown
            if (!com.darkempire78.opencalculator.securegallery.PinAttemptManager.canAttempt()) {
                Toast.makeText(this, "Access temporarily disabled.", Toast.LENGTH_SHORT).show()
                return
            }
            val gallery = com.darkempire78.opencalculator.securegallery.GalleryManager.findGalleryByPin(pin)
            if (gallery != null) {
                // Success: open gallery UI
                com.darkempire78.opencalculator.securegallery.TempPinHolder.pin = pin
                Toast.makeText(this, "Gallery unlocked!", Toast.LENGTH_SHORT).show()
                // TODO: Launch gallery activity/screen here
            } else {
                com.darkempire78.opencalculator.securegallery.PinAttemptManager.registerFailure()
                // Normal calculator behavior
                addSymbol(view, "!")
            }
        } else {
            addSymbol(view, "!")
        }
    }

    fun openAbout(menuItem: MenuItem) {
        val intent = Intent(this, AboutActivity::class.java)
        startActivity(intent, null)
    }

    fun openSettings(menuItem: MenuItem) {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent, null)
    }

    fun openDonation(menuItem: MenuItem) {
        DonationDialog(this, layoutInflater).openDonationDialog()
    }

    fun clearHistory(menuItem: MenuItem) {
        // Clear preferences
        MyPreferences(this@MainActivity).saveHistory(mutableListOf())
        // Clear drawer
        historyAdapter.clearHistory()
        checkEmptyHistoryForNoHistoryLabel()
    }

    private fun keyVibration(view: View) {
        if (MyPreferences(this).vibrationMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    private fun setErrorColor(errorStatus: Boolean) {
        // Only run if the color needs to be updated
        runOnUiThread {
            if (errorStatus != errorStatusOld) {
                // Set error color
                if (errorStatus) {
                    binding.input.setTextColor(
                        ContextCompat.getColor(
                            this,
                            R.color.calculation_error_color
                        )
                    )
                    binding.resultDisplay.setTextColor(
                        ContextCompat.getColor(
                            this,
                            R.color.calculation_error_color
                        )
                    )
                }
                // Clear error color
                else {
                    binding.input.setTextColor(ContextCompat.getColor(this, R.color.text_color))
                    binding.resultDisplay.setTextColor(
                        ContextCompat.getColor(
                            this,
                            R.color.text_second_color
                        )
                    )
                }
            }
        }
    }

    // --- Helper function stubs to resolve unresolved references ---

private fun updateDisplay(view: View, value: String) {
    // TODO: Implement display update logic
    // Example: binding.input.append(value)
}

private fun checkEmptyHistoryForNoHistoryLabel() {
    // TODO: Implement logic to show/hide no history label
}

private fun enableOrDisableScientistMode() {
    // TODO: Implement scientist mode toggle
}

private fun toggleDegreeMode() {
    // TODO: Implement degree/radian mode toggle
}

private fun updateResultDisplay() {
    // TODO: Implement result display update
}

private fun addSymbol(view: View, symbol: String) {
    // TODO: Implement symbol addition logic
    // Example: binding.input.append(symbol)
}  

fun percent(view: View) {
    // TODO: Implement percent button logic
    // Example: addSymbol(view, "%")
}

}            

