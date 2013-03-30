/*
 * Copyright 2012 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.battlelancer.seriesguide.service;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;

import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.provider.SeriesGuideDatabase.Tables;
import com.battlelancer.seriesguide.ui.EpisodesActivity;
import com.battlelancer.seriesguide.ui.QuickCheckInActivity;
import com.battlelancer.seriesguide.ui.SeriesGuidePreferences;
import com.battlelancer.seriesguide.ui.UpcomingRecentActivity;
import com.battlelancer.seriesguide.util.ImageProvider;
import com.battlelancer.seriesguide.util.Lists;
import com.battlelancer.seriesguide.util.Utils;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.seriesguide.R;

import java.util.List;

public class NotificationService extends IntentService {

    private static final boolean DEBUG = false;

    private static final int REQUEST_CODE_SINGLE_EPISODE = 2;

    private static final int REQUEST_CODE_MULTIPLE_EPISODES = 3;

    private static final int REQUEST_CODE_ACTION_CHECKIN = 4;

    private static final long[] VIBRATION_PATTERN = new long[] {
            0, 100, 200, 100, 100, 100
    };

    private static final String[] PROJECTION = new String[] {
            Tables.EPISODES + "." + Episodes._ID, Episodes.TITLE, Episodes.FIRSTAIREDMS,
            Shows.TITLE, Shows.NETWORK, Episodes.NUMBER, Episodes.SEASON, Shows.POSTER,
            Episodes.OVERVIEW
    };

    // by airdate, then by show, then lowest number first
    private static final String SORTING = Episodes.FIRSTAIREDMS + " ASC," + Shows.TITLE + " ASC,"
            + Episodes.NUMBER + " ASC";

    // only within time frame, unwatched episodes
    private static final String SELECTION = Episodes.FIRSTAIREDMS + ">=?"
            + Episodes.SELECTION_NOWATCHED;

    interface NotificationQuery {
        int _ID = 0;

        int TITLE = 1;

        int FIRSTAIREDMS = 2;

        int SHOW_TITLE = 3;

        int NETWORK = 4;

        int NUMBER = 5;

        int SEASON = 6;

        int POSTER = 7;

        int OVERVIEW = 8;
    }

    public NotificationService() {
        super("Episode Notification Service");
        setIntentRedelivery(false);
    }

    @TargetApi(16)
    @Override
    protected void onHandleIntent(Intent intent) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // unschedule notification service wake-ups for disabled notifications
        // and non-supporters
        if (!prefs.getBoolean(SeriesGuidePreferences.KEY_NOTIFICATIONS_ENABLED, true)
                || !Utils.isSupporterChannel(this)) {
            // cancel any pending alarm
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(this, OnAlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, 0);
            am.cancel(pi);

            resetLastEpisodeAirtime(prefs);

            return;
        }

        long wakeUpTime = 0;
        final long fakeNow = Utils.getFakeCurrentTime(prefs);
        StringBuilder selection = new StringBuilder(SELECTION);

        boolean isFavsOnly = prefs.getBoolean(SeriesGuidePreferences.KEY_NOTIFICATIONS_FAVONLY,
                true);
        if (isFavsOnly) {
            selection.append(Shows.SELECTION_FAVORITES);
        }

        boolean isNoSpecials = prefs.getBoolean(SeriesGuidePreferences.KEY_ONLY_SEASON_EPISODES,
                false);
        if (isNoSpecials) {
            selection.append(Episodes.SELECTION_NOSPECIALS);
        }

        // get episodes which air now beginning from 15 mins ago
        final Cursor upcomingEpisodes = getContentResolver().query(Episodes.CONTENT_URI_WITHSHOW,
                PROJECTION, selection.toString(), new String[] {
                    String.valueOf(fakeNow - 15 * DateUtils.MINUTE_IN_MILLIS)
                }, SORTING);

        if (upcomingEpisodes != null) {
            // find episodes which are within the notification threshold (user
            // set)
            int count = 0;
            final List<Integer> notifyPositions = Lists.newArrayList();
            int notificationThreshold = Integer.parseInt(prefs.getString(
                    SeriesGuidePreferences.KEY_NOTIFICATIONS_THRESHOLD, "60"));
            if (DEBUG) {
                // a week, for debugging (use only one show to get single
                // episode notifications)
                notificationThreshold = 10080;
                // notify again for same episodes
                resetLastEpisodeAirtime(prefs);
            }
            final long latestTimeToInclude = fakeNow + DateUtils.MINUTE_IN_MILLIS
                    * notificationThreshold;
            long latestTimeNotifiedAbout = prefs.getLong(
                    SeriesGuidePreferences.KEY_NOTIFICATIONS_LATEST_NOTIFIED, 0);
            while (upcomingEpisodes.moveToNext()) {
                final long airtime = upcomingEpisodes.getLong(NotificationQuery.FIRSTAIREDMS);
                if (airtime <= latestTimeToInclude) {
                    count++;
                    // only add those we didn't already notify about
                    if (latestTimeNotifiedAbout < airtime) {
                        notifyPositions.add(count);
                    }
                } else {
                    break;
                }
            }

            // notify if we found any episodes
            if (notifyPositions.size() > 0) {
                // store latest air time of all episodes we notified about
                upcomingEpisodes.moveToPosition(notifyPositions.get(notifyPositions.size() - 1));
                long latestAirtime = upcomingEpisodes.getLong(NotificationQuery.FIRSTAIREDMS);
                prefs.edit().putLong(SeriesGuidePreferences.KEY_NOTIFICATIONS_LATEST_NOTIFIED,
                        latestAirtime).commit();

                onNotify(prefs, upcomingEpisodes, count);
            }

            // find wake up time x (user-set) minutes before next episode airs
            upcomingEpisodes.moveToPosition(-1);
            while (upcomingEpisodes.moveToNext()) {
                final long airtime = upcomingEpisodes.getLong(NotificationQuery.FIRSTAIREDMS);
                if (airtime > latestTimeToInclude) {
                    wakeUpTime = Utils.convertToFakeTime(airtime, prefs, false)
                            - DateUtils.MINUTE_IN_MILLIS * notificationThreshold;
                    break;
                }
            }

            upcomingEpisodes.close();
        }

        // set a default wake-up time if there are no future episodes for now
        if (wakeUpTime == 0) {
            wakeUpTime = System.currentTimeMillis() + 6 * DateUtils.HOUR_IN_MILLIS;
        }

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(this, OnAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, 0);
        am.set(AlarmManager.RTC_WAKEUP, wakeUpTime, pi);
    }

    /**
     * Resets the air time of the last notified about episode. Afterwards
     * notifications for episodes may appear, which were already notified about.
     */
    public static void resetLastEpisodeAirtime(final SharedPreferences prefs) {
        prefs.edit().putLong(SeriesGuidePreferences.KEY_NOTIFICATIONS_LATEST_NOTIFIED, 0)
                .commit();
    }

    private void onNotify(final SharedPreferences prefs, final Cursor upcomingEpisodes, int count) {
        final Context context = getApplicationContext();
        CharSequence tickerText = "";
        CharSequence contentTitle = "";
        CharSequence contentText = "";
        PendingIntent contentIntent = null;

        // notification sound
        final String ringtoneUri = prefs.getString(SeriesGuidePreferences.KEY_RINGTONE,
                "content://settings/system/notification_sound");
        // vibration
        final boolean isVibrating = prefs.getBoolean(SeriesGuidePreferences.KEY_VIBRATE,
                false);

        if (count == 1) {
            // notify in detail about one episode
            upcomingEpisodes.moveToFirst();
            final String showTitle = upcomingEpisodes
                    .getString(NotificationQuery.SHOW_TITLE);
            final String airs = Utils.formatToTimeAndDay(
                    upcomingEpisodes.getLong(NotificationQuery.FIRSTAIREDMS), this)[0];
            final String network = upcomingEpisodes.getString(NotificationQuery.NETWORK);

            tickerText = getString(R.string.upcoming_show, showTitle);
            contentTitle = showTitle
                    + " "
                    + Utils.getEpisodeNumber(
                            PreferenceManager.getDefaultSharedPreferences(this),
                            upcomingEpisodes.getInt(NotificationQuery.SEASON),
                            upcomingEpisodes.getInt(NotificationQuery.NUMBER));
            contentText = getString(R.string.upcoming_show_detailed, airs, network);

            Intent notificationIntent = new Intent(context, EpisodesActivity.class);
            notificationIntent.putExtra(EpisodesActivity.InitBundle.EPISODE_TVDBID,
                    upcomingEpisodes.getInt(NotificationQuery._ID));
            contentIntent = PendingIntent.getActivity(context, REQUEST_CODE_SINGLE_EPISODE,
                    notificationIntent, 0);
        } else if (count > 1) {
            // notify about multiple episodes
            tickerText = getString(R.string.upcoming_episodes);
            contentTitle = getString(R.string.upcoming_episodes) + " ("
                    + String.valueOf(count) + ")";
            contentText = getString(R.string.upcoming_display);

            Intent notificationIntent = new Intent(context, UpcomingRecentActivity.class);
            contentIntent = PendingIntent.getActivity(context, REQUEST_CODE_MULTIPLE_EPISODES,
                    notificationIntent, 0);
        }

        final NotificationCompat.Builder nb = new NotificationCompat.Builder(context);

        if (AndroidUtils.isJellyBeanOrHigher()) {
            // JELLY BEAN and above

            if (count == 1) {
                // single episode
                upcomingEpisodes.moveToFirst();
                final String imagePath = upcomingEpisodes
                        .getString(NotificationQuery.POSTER);
                nb.setLargeIcon(ImageProvider.getInstance(context)
                        .getImage(imagePath, true));

                final String episodeTitle = upcomingEpisodes
                        .getString(NotificationQuery.TITLE);
                final String episodeSummary = upcomingEpisodes
                        .getString(NotificationQuery.OVERVIEW);

                final SpannableStringBuilder bigText = new SpannableStringBuilder();
                bigText.append(episodeTitle);
                bigText.setSpan(new ForegroundColorSpan(Color.WHITE), 0, bigText.length(),
                        0);
                bigText.append("\n");
                bigText.append(episodeSummary);

                nb.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText)
                        .setSummaryText(contentText));

                // Action button to check in
                Intent checkInActionIntent = new Intent(context, QuickCheckInActivity.class);
                checkInActionIntent.putExtra(QuickCheckInActivity.InitBundle.EPISODE_TVDBID,
                        upcomingEpisodes.getInt(NotificationQuery._ID));
                PendingIntent checkInIntent = PendingIntent.getActivity(context,
                        REQUEST_CODE_ACTION_CHECKIN,
                        checkInActionIntent, 0);
                nb.addAction(R.drawable.ic_action_checkin, getString(R.string.checkin),
                        checkInIntent);
            } else {
                // multiple episodes
                NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

                // display at most the first five
                final int displayCount = Math.min(count, 5);
                for (int i = 0; i < displayCount; i++) {
                    if (upcomingEpisodes.moveToPosition(i)) {
                        // add show title, air time and network
                        final SpannableStringBuilder lineText = new SpannableStringBuilder();
                        lineText.append(upcomingEpisodes
                                .getString(NotificationQuery.SHOW_TITLE));
                        lineText.setSpan(new ForegroundColorSpan(Color.WHITE), 0,
                                lineText.length(), 0);
                        lineText.append(" ");
                        String airs = Utils.formatToTimeAndDay(
                                upcomingEpisodes.getLong(NotificationQuery.FIRSTAIREDMS),
                                this)[0];
                        String network = upcomingEpisodes
                                .getString(NotificationQuery.NETWORK);
                        lineText.append(getString(R.string.upcoming_show_detailed, airs,
                                network));
                        inboxStyle.addLine(lineText);
                    }
                }

                // tell if we could not display all episodes
                if (count > 5) {
                    inboxStyle.setSummaryText(getString(R.string.more, count - 5));
                }

                nb.setStyle(inboxStyle);
            }
        } else {
            // ICS and below

            if (count == 1) {
                // single episode
                upcomingEpisodes.moveToFirst();
                final String posterPath = upcomingEpisodes
                        .getString(NotificationQuery.POSTER);
                nb.setLargeIcon(ImageProvider.getInstance(context).getImage(posterPath,
                        true));
            }
        }

        // If the string is empty, the user chose silent...
        if (ringtoneUri.length() != 0) {
            // ...otherwise set the specified ringtone
            nb.setSound(Uri.parse(ringtoneUri));
        }
        if (isVibrating) {
            nb.setVibrate(VIBRATION_PATTERN);
        }
        nb.setDefaults(Notification.DEFAULT_LIGHTS);
        nb.setWhen(System.currentTimeMillis());
        nb.setAutoCancel(true);
        nb.setTicker(tickerText);
        nb.setContentTitle(contentTitle);
        nb.setContentText(contentText);
        nb.setContentIntent(contentIntent);
        nb.setSmallIcon(R.drawable.ic_notification);
        nb.setPriority(NotificationCompat.PRIORITY_DEFAULT);

        // build the notification
        Notification notification = nb.build();

        // use string resource id, always unique within app
        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(R.string.upcoming_show, notification);
    }
}
