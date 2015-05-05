
// 'input' is an array of files to upload to Confluence with the ability 
//          to configure a different parent page for each file.
//
// Attributes
// - 'file': absolute or relative path to the asciidoc generated html file to be exported
// - 'ancestorId': the id of the parent page in Confluence; leave this empty if a new parent shall be created in the space
input = [
    	[ file: "asciidocOutput1.html", ancestorId: '' ],
    	[ file: "asciidocOutput2.html", ancestorId: 123456 ]
	]

// endpoint of the confluenceAPI (REST) to be used
confluenceAPI = 'https://[yourServer]/[context]/rest/api/'

// the key of the confluence space to write to
confluenceSpaceKey = 'asciidoc'

// variable to determine whether ".sect2" sections shall be split from the current page into subpages
confluenceCreateSubpages = false

// the pagePrefix will be a prefix for each page title
// use this if you only have access to one confluence space but need to store several
// pages with the same title - a different pagePrefix will make them unique
confluencePagePrefix = ''

// username:password of an account which has the right permissions to create and edit
// confluence pages in the given space.
// if you want to store it securely, fetch it from some external storage.
// you might even want to prompt the user for the password
confluenceCredentials = 'user:password'.bytes.encodeBase64().toString()

