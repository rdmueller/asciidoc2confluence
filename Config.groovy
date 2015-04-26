
// the asciidoc generated html file to be exported
    input = "asciidocOutput.html"

// endpoint of the confluenceAPI (REST) to be used
    confluenceAPI = 'https://[yourServer]/[context]/rest/api/'

// the key of the confluence space to write to
    confluenceSpaceKey = 'arc42'

// the pagePrefix will be a prefix for each page title
// use this if you only have access to one confluence space but need to store several
// pages with the same title - a different pagePrefix will make them unique
    confluencePagePrefix = ''

// username:password of an account which has the right permissions to create and edit
// confluence pages in the given space.
// if you want to store it securely, fetch it from some external storage.
// you might even want to prompt the user for the password
    confluenceCredentials = 'user:password'.bytes.encodeBase64().toString()

