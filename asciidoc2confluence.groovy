/**
 * Created by Ralf D. Müller on 03.09.2014.
 * https://github.com/rdmueller/asciidoc2confluence
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
import groovyx.net.http.EncoderRegistry
import groovyx.net.http.ContentType

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
        println error.response.dump()
        println error.response.data
        throw error
    }
}
//takes an html page and tries to normalize it
//by applying some tranformations
String normalize (def htmlPage) {
        page = Jsoup.parse(htmlPage)
        page.select("*").each { element ->
            if (!element.hasText() && element.nodeName() in ['a','div', 'i']) {
                element.remove()
            }
            if (element.nodeName() in ['caption']) {
                element.unwrap()
            }
            if (element.nodeName() in (['a']+(1..6).collect{"h"+it})) {
                element.removeAttr('id')
            }
        }
        page = page.html()
                            .replaceAll("(?sm)[ \t]+\n","\n")
                            .replaceAll(">[ ]+<","> <")
                            .replaceAll(';[ ]+"',';"')
                            .replaceAll('(?sm)<table([^>]*)>[ \t\n]+','<table$1>')
                            .replaceAll('center">','center;">')
                            //remove decimal places for some style attributes
                            .replaceAll(':[ \t]*([0-9]+)[.]0%',':$1%')
                            .replaceAll('(<tbody>|</tbody>)','')
                            //whitespaces
                            .replaceAll("\r",'')
                            .replaceAll("\t",'    ')
}
// the create-or-update functionality for confluence pages
def pushToConfluence = { pageTitle, pageBody, parentId ->
    def api = new RESTClient(config.confluenceAPI)
    def headers = [
                    'Authorization': 'Basic ' + config.confluenceCredentials,
                    'Content-Type':'application/json; charset=utf-8'
                  ]
    //this fixes the encoding
    api.encoderRegistry = new EncoderRegistry( charset: 'utf-8' )                   
    //try to get an existing page
    def page
    localPage = pageBody.toString().trim()
    //modify local page in order to match the internal confluence storage representation a bit better
    //definition lists are not displayed by confluence, so turn them into tables
    localPage = localPage.replaceAll('<dl>','<table>')
                         .replaceAll('</dl>','</table>')
                         .replaceAll('<dt[^>]*>','<tr><th>')
                         .replaceAll('</dt>','</th>')
                         .replaceAll('<dd>','<td>')
                         .replaceAll('</dd>','</td></tr>')
    def request = [
            type : 'page',
            title: config.confluencePagePrefix + pageTitle,
            space: [
                    key: config.confluenceSpaceKey
            ],
            body : [
                    storage: [
                            value         : localPage,
                            representation: 'storage'
                    ]
            ]
    ]
    if (parentId) {
        request.ancestors = [
                [ type: 'page', id: parentId]
        ]
    }
    trythis {
        page = api.get(path: 'content', 
                       query: [
                            'spaceKey': config.confluenceSpaceKey,
                            'title'   : config.confluencePagePrefix + pageTitle,
                            'expand'  : 'body.storage,version'
                       ], headers: headers).data.results[0]
    }
    if (page) {
        println "found existing page: " + page.id +" version "+page.version.number

        //normalize both pages (local and found remote) a little bit so that they are better comparable

        def remotePage = page.body.storage.value.toString().trim()

        //try to compare pages by normalizing them
        normalizedPage = normalize(localPage)
        remotePage = normalize(remotePage)

        if (normalizedPage == remotePage) {
            println "page hasn't changed!"
        } else {
            //write some debug output
            //can be analyzed with a tool like beyond compare 
            new File("local/${pageTitle}.html").write(normalizedPage)
            new File("remote/${pageTitle}.html").write(remotePage)
            trythis {
                // update page
                // https://developer.atlassian.com/display/CONFDEV/Confluence+REST+API+Examples#ConfluenceRESTAPIExamples-Updatingapage
                request.id      = page.id
                request.version = [number: (page.version.number as Integer) + 1]
                def res = api.put(contentType: ContentType.JSON,
                                  requestContentType : ContentType.JSON,
                                  path: 'content/' + page.id, body: request, headers: headers)
            }
            println "updated page"
            return page.id
        }
    } else {
        //create a page
        trythis {
            page = api.post(contentType: ContentType.JSON,
                            requestContentType : ContentType.JSON,
                            path: 'content', body: request, headers: headers)
        }
        println "created page "+page?.data?.id
        return page?.data?.id
    }
}
def html = new File(config.input).getText('utf-8')
def dom = Jsoup.parse(html,'utf-8')
// <div class="sect1"> are the main headings
// let's extract these and push them to confluence
def masterid = pushToConfluence "Main Page", "this shall be the main page under which all other pages are created", null
//init folder structure for debugging
['local','remote'].each { dir ->
    new File(dir).deleteDir()
    new File(dir+'/.').mkdirs()
}

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