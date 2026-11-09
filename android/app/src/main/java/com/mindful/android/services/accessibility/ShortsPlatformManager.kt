package com.mindful.android.services.accessibility

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.mindful.android.AppConstants.FACEBOOK_PACKAGE
import com.mindful.android.AppConstants.INSTAGRAM_PACKAGE
import com.mindful.android.AppConstants.REDDIT_PACKAGE
import com.mindful.android.AppConstants.SNAPCHAT_PACKAGE
import com.mindful.android.AppConstants.YOUTUBE_CLIENT_PACKAGE_SUFFIX
import com.mindful.android.AppConstants.YOUTUBE_PACKAGE
import com.mindful.android.enums.PlatformFeatures
import com.mindful.android.helpers.storage.SharedPrefsHelper
import com.mindful.android.models.Wellbeing
import org.jetbrains.annotations.Contract


class ShortsPlatformManager(
    private val context: Context,
    private val blockedContentGoBack: () -> Unit,
    private val showBlockedToast: () -> Unit,
) {

    // Instagram needs a gentler exit than a system Back press (see leaveInstagramReels)
    private var instagramActed = false
    private var instagramActedAt = 0L

    // Short content time is tracked separately for every platform (keyed by package name)
    private val lastShortsEventAt = HashMap<String, Long>()
    private var lastTimeSaved = 0L
    private val shortsScreenTimeMs =
        HashMap<String, Long>(SharedPrefsHelper.getShortsScreenTimeByPlatform(context))

    @Synchronized
    fun resetShortsScreenTime() {
        shortsScreenTimeMs.clear()
        lastShortsEventAt.clear()
        SharedPrefsHelper.resetShortsScreenTime(context)
    }

    /**
     * Checks if a blocked short-form content feature is open and applies restrictions.
     *
     * @param packageName The package name of the current app in focus.
     * @param node The root `AccessibilityNodeInfo` of the current screen.
     * @param wellbeing The user's `WellBeingSettings`, including blocked features and time limits.
     */
    fun blockDistraction(
        packageName: String,
        node: AccessibilityNodeInfo,
        wellbeing: Wellbeing,
    ) {
        // Use default youtube package for unofficial clients too
        val resolvedPackage =
            if (packageName.contains(YOUTUBE_CLIENT_PACKAGE_SUFFIX)) YOUTUBE_PACKAGE
            else packageName

        val blockedFeatures = wellbeing.blockedFeatures

        /// Check if blocking is enabled for platforms
        val isFeatureOpen = when (resolvedPackage) {
            INSTAGRAM_PACKAGE -> isInstagramFeatureOpen(node, blockedFeatures)
            SNAPCHAT_PACKAGE -> isSnapchatFeatureOpen(node, blockedFeatures)
            FACEBOOK_PACKAGE -> isFacebookFeatureOpen(node, blockedFeatures)
            REDDIT_PACKAGE -> isRedditFeatureOpen(node, blockedFeatures)
            YOUTUBE_PACKAGE -> isYoutubeFeatureOpen(node, blockedFeatures)
            else -> false
        }

        if (isFeatureOpen) {
            maxAllowedDuration[resolvedPackage]?.let {
                updateShortsScreenTime(
                    platform = resolvedPackage,
                    allowedShortContentTimeMs = wellbeing.allowedShortsTimeMs,
                    maxAllowedDuration = it,
                    onLimitReached = { onShortsBlocked(resolvedPackage, node) },
                )
            }
        } else if (resolvedPackage == INSTAGRAM_PACKAGE) {
            // Reels is gone from screen, so the next visit may be acted on again
            instagramActed = false
        }
    }

    /**
     * Handles a blocked short-form feature. Instagram gets a one-shot in-app exit,
     * every other platform keeps the original global Back behaviour.
     */
    private fun onShortsBlocked(packageName: String, root: AccessibilityNodeInfo) {
        if (packageName != INSTAGRAM_PACKAGE) {
            blockedContentGoBack.invoke()
            return
        }

        // Act once per Reels visit. If Reels is still showing after the retry window
        // the earlier action did not work, so try again.
        val now = System.currentTimeMillis()
        if (instagramActed && now - instagramActedAt < INSTAGRAM_RETRY_MS) return
        instagramActed = true
        instagramActedAt = now

        if (leaveInstagramReels(root)) showBlockedToast()
        else {
            Log.d(TAG, "onShortsBlocked: no in-app exit found, falling back to global back")
            blockedContentGoBack.invoke()
        }
    }

    /**
     * Leaves Instagram Reels without pressing the system Back button. A system Back from the
     * Reels tab can close Instagram entirely, and Instagram then reopens on Reels, which
     * blocks again, so the app could never be opened again.
     *
     * - Reels opened from the bottom tab: the tab bar is visible, so tap the Home tab.
     * - Reels opened from the feed or a profile: no tab bar, but the viewer has its own
     *   Back button as the first child of the start action buttons.
     *
     * @return true if an in-app control was clicked.
     */
    private fun leaveInstagramReels(root: AccessibilityNodeInfo): Boolean {
        val homeTab = root
            .findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/feed_tab")
            .firstOrNull { it.isVisibleToUser }

        if (homeTab != null) {
            val clicked = homeTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "leaveInstagramReels: tapped Home tab, success=$clicked")
            return clicked
        }

        val backButton = root
            .findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/clips_action_bar_start_action_buttons")
            .firstOrNull()
            ?.takeIf { it.childCount > 0 }
            ?.getChild(0)
            ?.takeIf { it.isClickable }

        if (backButton != null) {
            val clicked = backButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "leaveInstagramReels: tapped viewer Back button, success=$clicked")
            return clicked
        }

        return false
    }

    /**
     * Checks if a short-form content website is open in the browser based on WellBeingSettings.
     *
     * @param wellbeing The WellBeingSettings model indicating which platforms are blocked.
     * @param url      The URL text from the browser.
     * @return True if a blocked short-form content website is open, false otherwise.
     */
    fun checkAndBlockShortsOnBrowser(wellbeing: Wellbeing, url: String): Boolean {
        val platform: String? = when {
            PlatformFeatures.INSTAGRAM_REELS in wellbeing.blockedFeatures
                    && doesUrlContainsAnyElement(mInstaReelUrls, url) -> INSTAGRAM_PACKAGE

            PlatformFeatures.INSTAGRAM_EXPLORE in wellbeing.blockedFeatures
                    && doesUrlContainsAnyElement(mInstaExploreUrls, url) -> INSTAGRAM_PACKAGE

            PlatformFeatures.YOUTUBE_SHORTS in wellbeing.blockedFeatures
                    && doesUrlContainsAnyElement(mYtShortUrls, url) -> YOUTUBE_PACKAGE

            PlatformFeatures.FACEBOOK_REELS in wellbeing.blockedFeatures
                    && doesUrlContainsAnyElement(mFbReelUrls, url) -> FACEBOOK_PACKAGE

            PlatformFeatures.SNAPCHAT_SPOTLIGHT in wellbeing.blockedFeatures
                    && doesUrlContainsAnyElement(mSnapSpotlightUrls, url) -> SNAPCHAT_PACKAGE

            PlatformFeatures.SNAPCHAT_DISCOVER in wellbeing.blockedFeatures
                    && doesUrlContainsAnyElement(mSnapDiscoverUrls, url) -> SNAPCHAT_PACKAGE

            else -> null
        }

        if (platform != null) {
            updateShortsScreenTime(platform, wellbeing.allowedShortsTimeMs)
            return true
        }

        return false
    }

    /**
     * Updates the screen time spent on short-form content of one platform and blocks access
     * if the allowed time is exceeded for that platform.
     *
     * @param platform The package name of the platform whose time is tracked.
     * @param allowedShortContentTimeMs The maximum time allowed for short-form content on each platform.
     * @param maxAllowedDuration The maximum duration considered for a single short-form content session.
     */
    @Synchronized
    private fun updateShortsScreenTime(
        platform: String,
        allowedShortContentTimeMs: Long,
        maxAllowedDuration: Long = 30 * 1000L,
        onLimitReached: () -> Unit = blockedContentGoBack,
    ) {
        val platformTimeMs = shortsScreenTimeMs[platform] ?: 0L

        // Check if limit is exhausted
        if (allowedShortContentTimeMs < 0 || platformTimeMs > (allowedShortContentTimeMs + SAVING_INTERVAL_MS)) {
            onLimitReached.invoke()
            return
        }

        // Calculate screen time since last check
        val currentTime = System.currentTimeMillis()
        val lastEventAt = lastShortsEventAt[platform] ?: 0L
        val elapsedTime = if (lastEventAt != 0L) currentTime - lastEventAt else 0

        // Update only if elapsedTime is less than MAX_ALLOWED_DURATION otherwise user may have closed short content,
        shortsScreenTimeMs[platform] =
            platformTimeMs + (if (elapsedTime <= maxAllowedDuration) elapsedTime else 0)
        lastShortsEventAt[platform] = currentTime

        // Check if the minimum interval has passed before calling shared preferences
        if ((currentTime - lastTimeSaved) > SAVING_INTERVAL_MS) {
            SharedPrefsHelper.setShortsScreenTimeByPlatform(context, HashMap(shortsScreenTimeMs))
            lastTimeSaved = currentTime
            Log.d(TAG, "updateShortsScreenTime: shorts time saved: $shortsScreenTimeMs")
        }
    }


    companion object {
        private const val TAG = "Mindful.ShortsPlatformManager"

        // The minimum interval between saving short content's screen time in shared preferences
        private const val SAVING_INTERVAL_MS = (30 * 1000L)

        // How long to wait before retrying if Reels is still on screen after leaving it
        private const val INSTAGRAM_RETRY_MS = 2500L

        /**
         * Max allowed duration for each short content platform (based on the highest short length or duration)
         * If the interval between two short content block event is <= DURATION then it is considered that user is watching short content
         **/
        private val maxAllowedDuration = mapOf(
            INSTAGRAM_PACKAGE to (90 * 1000L),
            SNAPCHAT_PACKAGE to (60 * 1000L),
            FACEBOOK_PACKAGE to (90 * 1000L),
            REDDIT_PACKAGE to (60 * 1000L),
            YOUTUBE_PACKAGE to (3 * 60 * 1000L),
        )

        // Possible URLs of different short-form content platforms
        private val mInstaReelUrls = listOf("instagram.com/reels/", "m.instagram.com/reels/")
        private val mInstaExploreUrls = listOf("instagram.com/explore/", "m.instagram.com/explore/")

        private val mYtShortUrls = listOf("youtube.com/shorts/", "m.youtube.com/shorts/")
        private val mFbReelUrls = listOf("facebook.com/reel/", "m.facebook.com/reel/")
        private val mSnapSpotlightUrls = listOf(
            "snapchat.com/spotlight/",
            "m.snapchat.com/spotlight/",
            "web.snapchat.com/spotlight/"
        )
        private val mSnapDiscoverUrls = listOf(
            "snapchat.com/discover/",
            "m.snapchat.com/discover/",
            "web.snapchat.com/discover/"
        )

        private val mFbNodeTexts = listOf("Add a comment", "कमेंट जोड़ें…")


        /**
         * Checks if Instagram features (Reels or Search Feed) are open.
         */
        private fun isInstagramFeatureOpen(
            node: AccessibilityNodeInfo,
            blockedFeatures: Set<PlatformFeatures>,
        ): Boolean {
            return when {
                PlatformFeatures.INSTAGRAM_REELS in blockedFeatures &&
                        doesNodeByIdExists(node, "com.instagram.android:id/clips_video_container")
                -> true

                PlatformFeatures.INSTAGRAM_EXPLORE in blockedFeatures &&
                        doesNodeByIdExists(node, "com.instagram.android:id/action_bar_search_edit_text")
                -> true

                else -> false
            }
        }

        /**
         * Checks if YouTube Shorts is currently open.
         */
        private fun isYoutubeFeatureOpen(
            node: AccessibilityNodeInfo,
            blockedFeatures: Set<PlatformFeatures>,
        ): Boolean {
            return PlatformFeatures.YOUTUBE_SHORTS in blockedFeatures &&
                    doesNodeByIdExists(node, "${node.packageName}:id/reel_player_underlay")
        }

        /**
         * Checks if Snapchat Spotlight or Discover is open.
         */
        private fun isSnapchatFeatureOpen(
            node: AccessibilityNodeInfo,
            blockedFeatures: Set<PlatformFeatures>,
        ): Boolean {

            return when {
                PlatformFeatures.SNAPCHAT_SPOTLIGHT in blockedFeatures &&
                        doesNodeByIdExists(
                            node,
                            "com.snapchat.android:id/spotlight_card_static_thumbnail"
                        )
                -> true

                PlatformFeatures.SNAPCHAT_DISCOVER in blockedFeatures &&
                        doesNodeByIdExists(node, "com.snapchat.android:id/df_large_story")
                -> true

                else -> false
            }
        }

        /**
         * Checks if Facebook Reels is currently open.
         */
        private fun isFacebookFeatureOpen(
            node: AccessibilityNodeInfo,
            blockedFeatures: Set<PlatformFeatures>,
        ): Boolean {
            // TODO: Add more string translated from different languages for the node text
            //  as user may have set different language for facebook app

            if (PlatformFeatures.FACEBOOK_REELS in blockedFeatures) {
                for (text in mFbNodeTexts) {
                    if (node.findAccessibilityNodeInfosByText(text).isNotEmpty()) {
                        return true
                    }
                }
            }

            return false
        }

        /**
         * Checks if Reddit Shorts is open.
         */
        private fun isRedditFeatureOpen(
            node: AccessibilityNodeInfo,
            blockedFeatures: Set<PlatformFeatures>,
        ): Boolean {
            return PlatformFeatures.REDDIT_SHORTS in blockedFeatures && node.viewIdResourceName == "feed_vertical_pager"
        }

        /**
         * Checks if the URL contains any of the elements from the provided list of URLs.
         *
         * @param urlList The list of URL substrings to check against.
         * @param url     The URL to check.
         * @return True if the URL contains any element from the list, false otherwise.
         */
        @Contract(pure = true)
        private fun doesUrlContainsAnyElement(urlList: List<String>, url: String): Boolean {
            for (element in urlList) {
                if (url.contains(element)) return true
            }
            return false
        }

        /**
         * Checks whether an AccessibilityNodeInfo with the specified view ID exists as a descendant
         * of the given node.
         *
         * @param node   The parent AccessibilityNodeInfo to search within. This parameter must not be null.
         * @param viewId The ID of the view to look for.
         * @return `true` if a node with the specified view ID exists, `false` otherwise.
         */
        private fun doesNodeByIdExists(node: AccessibilityNodeInfo, viewId: String): Boolean {
            return node.findAccessibilityNodeInfosByViewId(viewId).isNotEmpty()
        }
    }
}