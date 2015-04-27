/**
 * Created by Ralf D. Müller on 03.09.2014.
 *
 * this script expects an HTML document created with AsciiDoctor
 * in the following style (default AsciiDoctor output)
 * <div class="sect1">
 *     <h2>Page Title</h2>
 *     <div class="sectionbody">
 *         <div class="sect2">
 *            <h3>Sub-Page Title</h3>
 *         </div>
 *         <div class="sect2">
 *            <h3>Sub-Page Title</h3>
 *         </div>
 *     </div>
 * </div>
 * <div class="sect1">
 *     <h2>Page Title</h2>
 *     ...
 * </div>
 *
 */

// some dependencies
@Grapes(
    [@Grab('org.jsoup:jsoup:1.7.3'),
    @Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.6' )]
)
import org.jsoup.Jsoup
import groovyx.net.http.RESTClient
import groovyx.net.http.HttpResponseException

String.metaClass.replaceAllEntities = {
    delegate
               .replaceAll('…','...')
               .replaceAll('„','&rdquo;')
               .replaceAll('“','&ldquo;')
               .replaceAll('”','&rdquo;')
               .replaceAll('–','&ndash;')
               .replaceAll('’','&rsquo;')
}
// configuration
def config = new ConfigSlurper().parse(new File('Config.groovy').text)

println config
// helper functions

// for getting better error message from the REST-API
void trythis (Closure action) {
    try {
        action.run()
    } catch (HttpResponseException error) {
        println "something went wrong - got an https response code "+error.response.status+":"
        println error.response.data
        throw error
    }
}
// the create-or-update functionality for confluence pages
def pushToConfluence = { pageTitle, pageBody, parentId ->
    def api = new RESTClient(config.confluenceAPI)
    def headers = ['Authorization': 'Basic ' + config.confluenceCredentials]

    //try to get an existing page
    def page
    trythis {
        page = api.get(path: 'content', query: [
                'spaceKey': config.confluenceSpaceKey,
                'title'   : config.confluencePagePrefix + pageTitle,
                'expand'  : 'body.storage,version'
        ], headers: headers).data.results[0]
    }
    if (page) {
        println "found existing page: " + page.id +" version "+page.version.number

        localPage = pageBody.toString().trim()
        //definition lists are not displayed by confluence, so turn them into tables
        localPage = localPage.replaceAll('<dl>','<table>')
                             .replaceAll('</dl>','</table>')
                             .replaceAll('<dt[^>]*>','<tr><th>')
                             .replaceAll('</dt>','</th>')
                             .replaceAll('<dd>','<td>')
                             .replaceAll('</dd>','</td></tr>')

        //normalize both pages (local and found remote) a little bit so that they are better comparable
        //TODO: should be done with jsoup and not with regexps
        //remove some empty tags
        def normalizedPage = localPage
        //remove some empty tags
        normalizedPage = normalizedPage.replaceAll('<(i|div|a)[^>]*>[ \t]*</\\1>','')
        //remove attributes from headline tags
                                       .replaceAll('<h([0-9])[^>]*>','<h$1>')
        //remove decimal places to some style attributes
                                       .replaceAll(':[ \t]*([0-9]+)[.]0%',':$1%')
        //replace some characters with the corresponding html entities
                                       .replaceAll('…','&hellip;')
                                       .replaceAll('„','&bdquo;')
                                       .replaceAll('“','&ldquo;')
                                       .replaceAll('–','&ndash;')
                                       .replaceAll('’','&rsquo;')
        //remove caption tags
                                       .replaceAll('<(/|)caption[^>]*>','')
        //I don't think we need this anymore after fixing the above line
                                       .replaceAll('; "',';"')
                                       .replaceAll('(<tbody>|</tbody>)','')
        //whitespaces
                                       .replaceAll("\r",'')
        //localPage = localPage.replaceAll("\n",'')
                                       .replaceAll("\t",'    ')
        //localPage = localPage.replaceAll('  ',' ')
        //very ugly temporary solution:
        //switch image attributes 'src="x"' and 'alt="x"'
                                       .replaceAll('<img (src="[^"]*") (alt="[^"]*")','<img $2 $1')
        //switch image attributes 'width="x"' and 'title="x"'
                                       .replaceAll('<img([^>]+)(width="[^"]*") (title="[^"]*")','<img $1$3 $2')
                                       .replaceAll('<a id="[^"]*"','<a')

        def remotePage = page.body.storage.value.toString().trim()

        remotePage = remotePage.replaceAll('<(i|div|a)[^>]*>[ \t]*</\\1>','')
                               .replaceAll(':[ \t]*([0-9]+)[.]0%',':$1%')
                               .replaceAll('(<tbody>|</tbody>)','')
        //whitespaces
                               .replaceAll("\r",'')
        //localPage = localPage.replaceAll("\n",'')
                               .replaceAll("\t",'    ')
        //localPage = localPage.replaceAll('  ',' ')

        //try to compare pages with jsoup
        normalizedPage = Jsoup.parse(localPage)
        normalizedPage.select("*").each { element ->
            if (!element.hasText() && element.nodeName() in ['a','div']) {
                element.remove()
            }
            if (element.nodeName() in ['caption']) {
                element.unwrap()
            }
        }
        normalizedPage = normalizedPage.html()
        remotePage = Jsoup.parse(remotePage)
        remotePage.select("*").each { element ->
            if (!element.hasText() && element.nodeName() in ['a','div']) {
                println ">>>>>>>>>>>>> "+element.nodeName()
                element.remove();
            }
        }
        remotePage = remotePage.html()

        if (normalizedPage == remotePage) {
            println "page hasn't changed!"
        } else {
            new File("local/${pageTitle}.html").write(normalizedPage)
            println " local transformed ".center(80,'=')
            println normalizedPage
            println " local original ".center(80,'=')
            println pageBody.toString()
            new File("remote/${pageTitle}.html").write(remotePage)
            println " remote ".center(80,'=')
            println remotePage
            /**
            println "="*80
            def storage = remotePage.split("\n")
            def local = localPage.split("\n")
            storage.eachWithIndex{ line, i->
                if (storage[i]!=local[i]) {
                    println "storage: "+storage[i]
                    println "local:   "+local[i]
                }
            }
             **/
            println "="*80
            trythis {
                // update page
                // https://developer.atlassian.com/display/CONFDEV/Confluence+REST+API+Examples#ConfluenceRESTAPIExamples-Updatingapage
                def request = [
                        id     : page.id,
                        type   : 'page',
                        title  : config.confluencePagePrefix + pageTitle.replaceAllEntities(),
                        version: [number: (page.version.number as Integer) + 1],
                        space  : [
                                key: config.confluenceSpaceKey
                        ],
                        body   : [
                                storage: [
                                        value         : localPage.replaceAllEntities(),
                                        representation: 'storage'
                                ]
                        ]
                ]
                if (parentId) {
                    request.ancestors = [
                            [ type: 'page', id: parentId]
                    ]
                }
                def res = api.put(contentType: 'application/json',
                        path: 'content/' + page.id, body: request, headers: headers)
            }
            println "updated page"
            return page.id
        }
    } else {
        //create a page
        trythis {
            def request = [
                    type : 'page',
                    title: config.confluencePagePrefix + pageTitle.replaceAllEntities(),
                    space: [
                            key: config.confluenceSpaceKey
                    ],
                    body : [
                            storage: [
                                    value         : pageBody.toString().replaceAllEntities(),
                                    representation: 'storage'
                            ]
                    ]
            ]
            if (parentId) {
                request.ancestors = [
                        [ type: 'page', id: parentId]
                ]
            }
            try {

            page = api.post(contentType: 'application/json',
                    path: 'content', body: request, headers: headers)
            } catch (Exception e) {
                //TODO: handle exception in a better way
                println "got an Exception :-("
                println e
                println request
                throw new RuntimeException("it seems that a special utf-8 character wasn't replaced with the right html entity")
            }
        }
        println "created page "+page?.data?.id
        return page?.data?.id
    }
}
def html = new File(config.input).getText('utf-8')
def dom = Jsoup.parse(html)
// <div class="sect1"> are the main headings
// let's extract these and push them to confluence
def masterid = pushToConfluence "Main Page", "this shall be the main page under which all other pages are created", null
new File('local').delete()
new File('remote').delete()
new File('remote/.').mkdirs()
new File('local/.').mkdirs()
dom.select('div.sect1').each { sect1 ->
    def pageTitle = sect1.select('h2').text()
    def pageBody = sect1.select('div.sectionbody')
    def subPages = []
    pageBody.select('div.sect2').each { sect2 ->
        def title = sect2.select('h3').text()
        sect2.select('h3').remove()
        def body = sect2
        subPages << [
                title: title,
                body: body
                ]
    }
    pageBody.select('div.sect2').remove()
    println pageTitle
    def thisSection = pushToConfluence pageTitle, pageBody, masterid
    subPages.each { subPage ->
        println "   "+subPage.title
        pushToConfluence subPage.title, subPage.body, thisSection
    }
}
""