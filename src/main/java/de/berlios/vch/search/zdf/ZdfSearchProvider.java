package de.berlios.vch.search.zdf;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.osgi.framework.ServiceException;
import org.osgi.service.log.LogService;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.IWebParser;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;
import de.berlios.vch.parser.XmlParserUtils;
import de.berlios.vch.search.ISearchProvider;

@Component
@Provides
public class ZdfSearchProvider implements ISearchProvider {

    private static final String BASE_URI = "http://www.zdf.de/ZDFmediathek/xmlservice/web";
    private static final int PREFERRED_THUMB_WIDTH = 300;

    @Requires(filter = "(instance.name=vch.parser.zdf)")
    private IWebParser parser;

    @Requires
    private LogService logger;

    @Override
    public String getName() {
        return "ZDFmediathek";
    }

    @Override
    public IOverviewPage search(String query) throws Exception {
        String uri = BASE_URI + "/detailsSuche";
        String post = "offset=0&maxLength=50&ak=web&searchString=" + query;
        String xml = HttpUtils.post(uri, null, post.getBytes("UTF-8"), "UTF-8");

        // parse the xml
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document content = builder.parse(new InputSource(new StringReader(xml)));

        String statusCode = XmlParserUtils.getTextContent(content, "statuscode");
        if (!"ok".equals(statusCode)) {
            throw new RuntimeException("Error: status code " + statusCode);
        }

        IOverviewPage opage = new OverviewPage();
        opage.setParser(getId());
        opage.setUri(new URI(uri));

        NodeList teasers = content.getElementsByTagName("teaser");
        for (int i = 0; i < teasers.getLength(); i++) {
            try {
                Node teaser = teasers.item(i);
                String type = XmlParserUtils.getTextContent(teaser, "type");
                if (!"video".equals(type)) {
                    continue;
                }

                String title = XmlParserUtils.getTextContent(teaser, "title");
                String description = XmlParserUtils.getTextContent(content, "detail");
                String id = XmlParserUtils.getTextContent(teaser, "assetId");

                VideoPage video = new VideoPage();
                video.setParser(getId());
                video.setTitle(title);
                video.setDescription(description);
                video.getUserData().put("id", id);
                video.setUri(new URI(BASE_URI + "/beitragsDetails?ak=web&id=" + id));
                opage.getPages().add(video);

                // parse the uri of the preview image
                Map<Integer, String> images = parsePreviewImages(teaser);
                if (images.size() > 0) {
                    List<Integer> sizes = new ArrayList<Integer>(images.keySet());
                    Collections.sort(sizes);
                    String thumbUri = images.get(getClosest(sizes, PREFERRED_THUMB_WIDTH));
                    video.setThumbnail(new URI(thumbUri));
                    // String biggest = images.get(sizes.get(sizes.size() - 1));
                }
            } catch (Exception e) {
                logger.log(LogService.LOG_ERROR, "Couldn't parse one of the search results", e);
            }
        }

        return opage;
    }

    private Map<Integer, String> parsePreviewImages(Node parent) {
        List<Node> images = new ArrayList<Node>();
        XmlParserUtils.getElementsByTagName(parent, "teaserimage", images);
        Map<Integer, String> imageUris = new HashMap<Integer, String>();
        for (int i = 0; i < images.size(); i++) {
            Node teaserimage = images.get(i);
            String size = teaserimage.getAttributes().getNamedItem("key").getNodeValue();
            int width = Integer.parseInt(size.substring(0, size.indexOf('x')));
            String uri = teaserimage.getTextContent();
            if (!uri.contains("fallback")) { // fallback URIs don't work at the moment (28.04.2013)
                imageUris.put(width, uri);
            }
        }
        return imageUris;
    }

    private static int getClosest(List<Integer> list, Integer n) {
        int closest = list.get(0);
        int distance = Math.abs(n - closest);
        for (Integer element : list) {
            int currentDistance = Math.abs(element - n);
            if (currentDistance < distance) {
                closest = element;
                distance = currentDistance;
            }
        }
        return closest;
    }

    @Override
    public String getId() {
        return "de.berlios.vch.parser.zdf.ZDFMediathekSearch";
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        if (page instanceof IOverviewPage) {
            return page;
        } else {
            if (parser == null) {
                throw new ServiceException("ZDFMediathek Parser is not available");
            }
            return parser.parse(page);
        }
    }
}
