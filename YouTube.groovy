import groovy.json.JsonSlurper
import org.restlet.engine.util.InternetDateFormat
import org.serviio.library.online.*

/**
 * YouTube plugin for Serviio.
 *
 * @author Eugene Khrustalev <eugene.khrustalev@gmail.com>
 */
class YouTube extends WebResourceUrlExtractor {

    final YOUTUBE_API_KEY = 'AIzaSyDW_6_BLqxfOnds4UplKunrJpCHH8-mEV4'

    final YOUTUBE_VALID_ADDR = '(?i)^https?://?(www\\.)?(youtube\\.com|youtu\\.be)'
    final YOUTUBE_API_URL = new URL('https://www.googleapis.com/youtube/v3/')

    final USER_AGENT = 'Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.1; Trident/6.0)'

    /**
     * Comment formats that you do not need play or your player not supported
     * @see https://tyrrrz.me/blog/reverse-engineering-youtube
     */
    final FORMATS = [
        (PreferredQuality.HIGH): [
            ['id': '38',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 3072p'],
            ['id': '37',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 1080p'],
            ['id': '85',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 1080p'],
            ['id': '22',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 720p'],
            ['id': '84',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 720p'],
            ['id': '95',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 720p'],
            ['id': '96',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 720p'],
            ['id': '46',  'mime': 'video/webm',  'description': 'WEBM/Vorbis/VP8 1080p'],
            ['id': '45',  'mime': 'video/webm',  'description': 'WEBM/Vorbis/VP8 720p'],
            ['id': '102', 'mime': 'video/webm',  'description': 'WEBM/Vorbis/VP8 720p'],
        ],
        (PreferredQuality.MEDIUM): [
            ['id': '59',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 480p'],
            ['id': '78',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 480p'],
            ['id': '83',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 480p'],
            ['id': '94',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 480p'],
            ['id': '44',  'mime': 'video/webm',  'description': 'WEBM/Vorbis/VP8 480p'],
            ['id': '101', 'mime': 'video/webm',  'description': 'WEBM/Vorbis/VP8 480p'],
            ['id': '35',  'mime': 'video/x-flv', 'description': 'FLV/H264/AAC 480p'],
        ],
        (PreferredQuality.LOW): [
            ['id': '18',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 360p'],
            ['id': '82',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 360p'],
            ['id': '93',  'mime': 'video/mp4',   'description': 'MP4/H264/AAC 360p'],
            ['id': '43',  'mime': 'video/webm',  'description': 'WEBM/Vorbis/VP8 360p'],
            ['id': '100', 'mime': 'video/webm',  'description': 'WEBM/Vorbis/VP8 360p'],
            ['id': '34',  'mime': 'video/x-flv', 'description': 'FLV/H264/AAC 360p'],
        ]
    ]

    @Override
    String getExtractorName() {
        return getClass().getName()
    }

    @Override
    boolean extractorMatches(URL url) {
        return url.toExternalForm() =~ YOUTUBE_VALID_ADDR
    }

    @Override
    protected WebResourceContainer extractItems(URL url, int maxItems) {
        if (!url.path) return null
        // URL points to video
        def regex = (url.path =~ /(?i)\/watch(\/(.*))?$/)
        if (regex) {
            def qs = parseQS(url.query)
            if (qs.containsKey('v')) {
                String videoId = qs['v']
                log("$url points to video $videoId")
                return extractItemsVideo(videoId)
            } else if (regex.count) {
                String videoId = regex[0][2]
                log("$url points to video $videoId")
                return extractItemsVideo(videoId)
            } else {
                log("$url points to video but parameter v=<videoId> not exists")
                return null
            }
        }
        // URL points to playlist
        regex = (url.path =~ /(?i)\/playlist(\/(.*))?$/)
        if (regex) {
            def qs = parseQS(url.query)
            if (qs.containsKey('list')) {
                String listId = qs['list']
                log("$url points to playlist $listId")
                return extractItemsPlaylist(listId, maxItems)
            } else if (regex.count) {
                String listId = regex[0][2]
                log("$url points to playlist $listId")
                return extractItemsPlaylist(listId, maxItems)
            } else {
                log("$url points to playlist but parameter list=<playlistId> not exists")
                return null
            }
        }
        // URL points to channel
        regex = (url.path =~ /(?i)\/channel\/([^\/]+)(\/.*)?$/)
        if (regex) {
            String channelId = regex[0][1]
            log("$url points to channel $channelId")
            return extractItemsChannel(channelId, maxItems)
        }
        // URL may point to video in format youtu.be/videoId
        regex = (url.path =~ /(?i)\/([^\/]+)(\/.*)?$/)
        if (regex) {
            String videoId = regex[0][1]
            if (videoId) {
                log("$url may point to video $videoId")
                return extractItemsVideo(videoId)
            }
        }
        log("Invalid url $url")
        return null
    }

    /**
     * Called from extractItems(URL,int) for single video URL
     * @param videoId youtube video identifier
     */
    private WebResourceContainer extractItemsVideo(String videoId) {
        log("Looking for videoId=$videoId...")
        def json = parseJson(new URL(YOUTUBE_API_URL, "videos?id=$videoId&part=snippet&key=$YOUTUBE_API_KEY"))
        if (json.items) {
            def container = new WebResourceContainer()
            json.items.each {
                def snippet = it.snippet
                def resourceItem = new WebResourceItem(title: snippet.title
                    , description: snippet.description
                    , additionalInfo: ['videoId': it.id, 'thumbnails': snippet.thumbnails])
                try {
                    resourceItem.releaseDate = new InternetDateFormat().parse(snippet.publishedAt)
                } catch (Exception ex) {
                    /* do nothing */
                }
                log("Found video: videoId=$videoId, title=${resourceItem.title}")
                container.title = snippet.title
                container.thumbnailUrl = extractThumbnail(snippet.thumbnails)
                container.items.add(resourceItem)
            }
            return container
        }
        log("No videos found for videoId=$videoId")
        return null
    }

    /**
     * Called from extractItems(URL,int) for playlist URL
     * @param playlistId youtube playlist identifier
     */
    private WebResourceContainer extractItemsPlaylist(String playlistId, int maxItems) {
        log("Looking for playlistId=$playlistId...")
        def json = parseJson(new URL(YOUTUBE_API_URL, "playlists?id=$playlistId&part=snippet&key=$YOUTUBE_API_KEY"))
        if (json.items && json.items.size() > 0) {
            def container = new WebResourceContainer()
            // Playlist info
            def snippet = json.items[0].snippet
            container.title = snippet.title
            container.thumbnailUrl = extractThumbnail(snippet.thumbnails)
            log("Looking for videos in playlist(id=$playlistId, title=${container.title})...")
            // Playlist items
            def pageToken = null, done = false
            def maxResults = maxItems <= 0 ? 50 : maxItems < 50 ? maxItems : 50
            outer:
            while (!done) {
                json = parseJson(new URL(YOUTUBE_API_URL, "playlistItems?playlistId=$playlistId&part=snippet&maxResults=$maxResults${pageToken ? '&pageToken=' + pageToken : ''}&key=$YOUTUBE_API_KEY"))
                if (json.items && json.items.size() > 0) {
                    pageToken = json.nextPageToken
                    done = pageToken == null
                    for (item in json.items) {
                        snippet = item.snippet
                        def videoId = null
                        if (snippet.contentDetails && snippet.contentDetails.videoId) {
                            videoId = snippet.contentDetails.videoId
                        } else if (snippet.resourceId && snippet.resourceId.kind == "youtube#video") {
                            videoId = snippet.resourceId.videoId
                        }
                        if (videoId) {
                            def resourceItem = new WebResourceItem(title: snippet.title
                                , description: snippet.description
                                , additionalInfo: ['videoId': videoId, 'thumbnails': snippet.thumbnails])
                            try {
                                resourceItem.releaseDate = new InternetDateFormat().parse(snippet.publishedAt)
                            } catch (Exception ex) {
                                /* do nothing */
                            }
                            log("Found video(id=$videoId, title=${resourceItem.title})")
                            container.items.add(resourceItem)
                            if (maxItems > 0 && container.items.size() >= maxItems) break outer
                        }
                    }
                } else {
                    break
                }
            }
            return container
        }
        log("No playlist found with playlistId=$playlistId")
        return null
    }

    /**
     * Called from extractItems(URL,int) for channel URL
     * @param channelId youtube channel identifier or @username
     */
    private WebResourceContainer extractItemsChannel(String channelId, int maxItems) {
        log("Looking for channelId=$channelId...")
        // Channel videos playlist
        def json = parseJson(new URL(YOUTUBE_API_URL, "channels?id=$channelId&part=snippet,contentDetails&key=$YOUTUBE_API_KEY"))
        def snippet = null
        String playlistId = null
        if (json && json.items) {
            for (item in json.items) {
                snippet = item.snippet
                playlistId = item.contentDetails?.relatedPlaylists?.uploads
                if (playlistId) break
            }
        }
        if (playlistId) {
            log("Found playlist(id=$playlistId) on channel(id=$channelId, title=${snippet.title})")
            def container = extractItemsPlaylist(playlistId, maxItems)
            if (container && container.items && snippet) {
                container.title = snippet.title ?: container.title
                container.thumbnailUrl = extractThumbnail(snippet.thumbnails)
            }
            return container
        }
        return null
    }

    @Override
    protected ContentURLContainer extractUrl(WebResourceItem item, PreferredQuality quality) {
        String videoId = item.additionalInfo.videoId
        String el = item.additionalInfo.detailPage ? '&el=detailpage' : ''
        log("Looking for URL for video(id=$videoId, title=${item.title}). detailpage=${!el.isEmpty()}...")
        if (videoId) {
            def info = openURL(new URL("https://www.youtube.com/get_video_info?video_id=$videoId$el&key=$YOUTUBE_API_KEY"), USER_AGENT)
            if (info) {
                def qs = parseQS(info)
                def formatId = null, formatMimeType = null, formatDescription = null
                if (qs.fmt_list) {
                    def lastIdx = -1
                    def fmtList = URLDecoder.decode(qs.fmt_list, 'UTF-8')
                    log("Video available in format:$fmtList")
                    fmtList.split(',').each {
                        def format = it.split('/', 2)[0]
                        // Find better format from available
                        for (int i = 0; i < FORMATS[quality].size(); i++) {
                            def entry = FORMATS[quality][i]
                            if (entry.id == format && (lastIdx == -1 || lastIdx < i)) {
                                formatId = entry.id
                                formatMimeType = entry.mime
                                formatDescription = entry.description
                                lastIdx = i
                                log("Selected video format(id=$formatId, mime=$formatMimeType, description=$formatDescription)")
                                break
                            }
                        }
                    }
                }
                if (qs.url_encoded_fmt_stream_map) {
                    def fmtStreamMap = parseQS(URLDecoder.decode(qs.url_encoded_fmt_stream_map, 'UTF-8'))
                    if (fmtStreamMap.url || fmtStreamMap.conn) {
                        def uri = new URI(URLDecoder.decode(fmtStreamMap.url ?: fmtStreamMap.conn, 'UTF-8'))
                        Date expiresOn = null
                        boolean rebuildQuery = false
                        if (uri.query) {
                            qs = parseQS(uri.query)
                            if (formatId && "$formatId" != "${qs.itag}") {
                                // Reconstruct URL for better format
                                log("Default video format(id=${qs.itag}) has been replaced to format(id=$formatId, mime=$formatMimeType, description=$formatDescription)")
                                qs.itag = formatId
                                qs.mime = formatMimeType
                                rebuildQuery = true
                            }
                            if (qs.expire) {
                                expiresOn = new Date(Long.parseLong(qs.expire) * 1000)
                                log("Video will be expired after $expiresOn")
                            }
                        } else if (formatId) {
                            qs.itag = formatId
                            qs.mime = formatMimeType
                            rebuildQuery = true
                        }
                        if (rebuildQuery) {
                            uri.query = qs.collect { k,v -> "$k=$v" }.join("&")
                        }
                        return new ContentURLContainer(contentUrl: uri.toString(),
                            cacheKey: "${getClass().getName()}-$videoId-$formatId",
                            thumbnailUrl: extractThumbnail(item.additionalInfo.thumbnails),
                            expiresOn: expiresOn, expiresImmediately: expiresOn == null,
                            userAgent: USER_AGENT
                         )
                    }
                }
            }
        }
        // If no URL extracted then recursive try with el=detailpage parameter
        if (!el) {
            log("No video URL extracted. Trying with 'el=detailpage' parameter...")
            item.additionalInfo.detailPage = true
            return extractUrl(item, quality)
        }
        log("No video URL extracted")
        return null
    }

    /**
     * Splits query string to map
     * @param query query string to split
     * @return
     */
    private Map parseQS(String query) {
        def params = [:]
        if (query) {
            query.split('&').each { param ->
                ambiguous:
                {
                    def pair = param.split('=', 2)
                    params[pair[0]] = pair.length > 1 ? pair[1] : ''
                }
            }
        }
        return params
    }

    /**
     * Executes HTTP GET request and parses response as JSON
     * @param url JSON url
     * @return map
     */
    private Map parseJson(URL url) {
        def response = openURL(url, USER_AGENT)
        if (response) {
            return new JsonSlurper().parseText(response)
        }
        return [:]
    }

    /**
     * Extract thumbnail from thumbnails list
     * @param thumbnails list of thumbnails
     * @return thumbnail url or null
     */
    private String extractThumbnail(Map thumbnails) {
        if (thumbnails) {
            for (type in ['maxres', 'high', 'medium', 'standard', 'default']) {
                def thumbnail = thumbnails[type]
                if (thumbnail && thumbnail.url) {
                    return thumbnail.url
                }
            }
        }
        return null
    }

    /**
     * Debug entry-point.
     *
     * @param args
     */
    static void main(args) {
        YouTube youtube = new YouTube()

        assert youtube.extractorMatches(new URL("http://youtu.be/B98jc8hdu9g"))
        assert youtube.extractorMatches(new URL("https://www.youtube.com/watch?v=B98jc8hdu9g"))
        assert youtube.extractorMatches(new URL("https://www.youtube.com/watch/B98jc8hdu9g"))
        assert youtube.extractorMatches(new URL("https://www.youtube.com/playlist?list=PLhW3qG5bs-L8T6v6DgsZo93DgYDmOF9u4"))
        assert youtube.extractorMatches(new URL("https://www.youtube.com/playlist/PLhW3qG5bs-L8T6v6DgsZo93DgYDmOF9u4"))

        assert youtube.extractorMatches(new URL("https://www.youtu.be/channel/UCcG10lsoPEHx49kgJL1SwIg/about"))
        assert youtube.extractorMatches(new URL("https://www.youtu.be/channel/UCcG10lsoPEHx49kgJL1SwIg/"))
        assert youtube.extractorMatches(new URL("https://www.youtu.be/channel/UCcG10lsoPEHx49kgJL1SwIg"))
        assert youtube.extractorMatches(new URL("https://www.youtu.be/channel/@KliN"))

        /*youtube.extractItems(new URL("https://www.youtube.com/watch?v=IewAfjZwTNM&list=PL-MZBJ-9MytfB3i0UvznoiNgH1J0pU6Kc"), -1)
        youtube.extractItems(new URL("https://www.youtube.com/watch?v=B98jc8hdu9g"), -1)
        youtube.extractItems(new URL("http://youtu.be/watch/B98jc8hdu9g"), -1)
        youtube.extractItems(new URL("https://www.youtube.com/playlist?list=PLhW3qG5bs-L8T6v6DgsZo93DgYDmOF9u4"), 30)
        youtube.extractItems(new URL("https://www.youtube.com/playlist?list=PL-MZBJ-9MytfB3i0UvznoiNgH1J0pU6Kc"), 30)

        youtube.extractItems(new URL("https://www.youtu.be/channel/UCcG10lsoPEHx49kgJL1SwIg/about"), 5)
        youtube.extractItems(new URL("https://www.youtu.be/channel/UCcG10lsoPEHx49kgJL1SwIg/"), 1)*/
//        youtube.extractItems(new URL("https://www.youtube.com/playlist/PLQ3I0JQjWkbpZEXClB6GqXEwhUr7OXykH"), 30)
//        youtube.extractItems(new URL("https://www.youtu.be/channel/UCcG10lsoPEHx49kgJL1SwIg"), -1)

        def container1 = youtube.extractItems(new URL("https://www.youtube.com/watch?v=VjMCh9HP0N0"), -1)
        assert container1.items
        def container2 = youtube.extractUrl(container1.items[0], PreferredQuality.HIGH)
        assert container2
    }
}
