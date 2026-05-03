function run() {
    var author = uriRoute.getValue("author");
    uriRoute.add("author", author);
    uriRoute.add("message", "Hello UriRoute!");
}
