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
    [@Grab('org.jsoup:jsoup:1.8.2'),
     @Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.6' )]
)
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.nodes.Entities.EscapeMode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Document.OutputSettings
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import groovyx.net.http.RESTClient
import groovyx.net.http.HttpResponseException
import groovyx.net.http.EncoderRegistry
import groovyx.net.http.ContentType
import java.security.MessageDigest

// configuration
def config
try {
    println "scriptBasePath: ${scriptBasePath}"
    config = new ConfigSlurper().parse(new File(scriptBasePath, 'Config.groovy').text)
} catch (Exception e) {
    // if no scriptBasePath is bound to this script, this might work
    config = new ConfigSlurper().parse(new File('Config.groovy').text)
}
println "Config: ${config}"

// helper functions

def MD5(String s) {
    MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
}

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

def parseAdmonitionBlock(block, String type) {
    content = block.select(".content").first()
    titleElement = content.select(".title")
    titleText = ''
    if(titleElement != null) {
        titleText = "<ac:parameter ac:name=\"title\">${titleElement.text()}</ac:parameter>"
        titleElement.remove()
    }
    block.after("<ac:structured-macro ac:name=\"${type}\">${titleText}<ac:rich-text-body>${content}</ac:rich-text-body></ac:structured-macro>")
    block.remove()
}

//modify local page in order to match the internal confluence storage representation a bit better
//definition lists are not displayed by confluence, so turn them into tables
//body can be of type Element or Elements
def parseBody(def body) {
    body.select('div.paragraph').unwrap()
    body.select('div.ulist').unwrap()
    body.select('div.sect3').unwrap()
    body.select('.admonitionblock.note').each { block ->
        parseAdmonitionBlock(block, "info")
    }
    body.select('.admonitionblock.warning').each { block ->
        parseAdmonitionBlock(block, "warning")
    }
    body.select('.admonitionblock.important').each { block ->
        parseAdmonitionBlock(block, "warning")
    }
    body.select('.admonitionblock.caution').each { block ->
        parseAdmonitionBlock(block, "note")
    }
    body.select('.admonitionblock.tip').each { block ->
        parseAdmonitionBlock(block, "tip")
    }
    body.select('div.title').wrap("<strong></strong>").unwrap()
    body.select('div.listingblock').wrap("<p></p>").unwrap()

    pageString = body.toString().trim()
            .replaceAll("<pre class=\".+\"><code( class=\".+\" data-lang=\".+\")?>", "<ac:structured-macro ac:name=\\\"code\\\"><ac:plain-text-body><![CDATA[")
            .replaceAll("</code></pre>", "]]></ac:plain-text-body></ac:structured-macro>")
            .replaceAll('<dl>','<table>')
            .replaceAll('</dl>','</table>')
            .replaceAll('<dt[^>]*>','<tr><th>')
            .replaceAll('</dt>','</th>')
            .replaceAll('<dd>','<td>')
            .replaceAll('</dd>','</td></tr>')
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
    localPage = parseBody(pageBody)

    def localHash = MD5(localPage)
    localPage = '<p><ac:structured-macro ac:name="toc"/></p>'+localPage             
    localPage += '<p><ac:structured-macro ac:name="children"/></p>'
    localPage += '<p style="display:none">hash: #'+localHash+'#</p>'

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

        //extract hash from remote page to see if it is different from local one

        def remotePage = page.body.storage.value.toString().trim()

        def remoteHash = remotePage =~ /(?ms)hash: #([^#]+)#/
        remoteHash = remoteHash.size()==0?"":remoteHash[0][1]

        if (remoteHash == localHash) {
            println "page hasn't changed!"
        } else {
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

config.input.each { input ->

    def html = new File(input.file).getText('utf-8')
    Document dom = Jsoup.parse(html, 'utf-8', Parser.xmlParser())
    dom.outputSettings().prettyPrint(false);//makes html() preserve linebreaks and spacing
    dom.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml); //This will ensure xhtml validity regarding entities
    dom.outputSettings().charset("UTF-8"); //does no harm :-)
    def masterid = input.ancestorId

    // if confluenceAncestorId is not set, create a new parent page
    if (!input.ancestorId) {
        masterid = pushToConfluence "Main Page", Jsoup.parse("this shall be the main page under which all other pages are created").select('*'), null
        println("New master page created with id ${masterid}")
    }

    // <div class="sect1"> are the main headings
    // let's extract these and push them to confluence
    dom.select('div.sect1').each { sect1 ->
        def pageTitle = sect1.select('h2').text()
        Elements pageBody = sect1.select('div.sectionbody')
        def subPages = []

        if (config.confluenceCreateSubpages) {
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
        } else {
            pageBody.select('div.sect2').unwrap()
        }
        println pageTitle
        def thisSection = pushToConfluence pageTitle, pageBody, masterid
        subPages.each { subPage ->
            println "   "+subPage.title
            pushToConfluence subPage.title, subPage.body, thisSection
        }
    }
}
""