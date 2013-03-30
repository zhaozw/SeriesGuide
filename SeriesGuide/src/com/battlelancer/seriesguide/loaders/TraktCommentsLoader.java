/*
 * Copyright 2013 Uwe Trottmann
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

package com.battlelancer.seriesguide.loaders;

import android.content.Context;
import android.os.Bundle;

import com.battlelancer.seriesguide.util.ServiceUtils;
import com.battlelancer.seriesguide.util.ShareUtils.ShareItems;
import com.battlelancer.seriesguide.util.Utils;
import com.jakewharton.apibuilder.ApiException;
import com.jakewharton.trakt.ServiceManager;
import com.jakewharton.trakt.TraktException;
import com.jakewharton.trakt.entities.Comment;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads up comments from trakt for a movie (tvdbId arg is 0), show (episode arg
 * is 0) or episode.
 */
public class TraktCommentsLoader extends GenericSimpleLoader<List<Comment>> {

    private static final String TAG = "TraktCommentsLoader";

    private Bundle mArgs;

    public TraktCommentsLoader(Context context, Bundle args) {
        super(context);
        mArgs = args;
    }

    @Override
    public List<Comment> loadInBackground() {
        int tvdbId = mArgs.getInt(ShareItems.TVDBID);
        int episode = mArgs.getInt(ShareItems.EPISODE);

        ServiceManager manager = ServiceUtils.getTraktServiceManager(getContext());
        List<Comment> comments = new ArrayList<Comment>();
        try {
            if (tvdbId == 0) {
                // movie comments
                int tmdbId = mArgs.getInt(ShareItems.TMDBID);
                comments = manager.movieService().comments(tmdbId).fire();
            } else if (episode == 0) {
                // show comments
                comments = manager.showService().comments(tvdbId).fire();
            } else {
                // episode comments
                int season = mArgs.getInt(ShareItems.SEASON);
                comments = manager.showService().episodeComments(tvdbId, season, episode)
                        .fire();
            }

        } catch (TraktException e) {
            Utils.trackExceptionAndLog(getContext(), TAG, e);
            return null;
        } catch (ApiException e) {
            Utils.trackExceptionAndLog(getContext(), TAG, e);
            return null;
        }

        return comments;
    }
}
