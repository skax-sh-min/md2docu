package com.md2docu.util;

import org.apache.batik.bridge.ExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.transcoder.ErrorHandler;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.util.ParsedURL;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

final class SvgRasterizer extends ImageTranscoder {

    private BufferedImage image;

    static BufferedImage rasterize(byte[] bytes) throws IOException, SAXException, TranscoderException {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            var document = parser.parse(new ByteArrayInputStream(bytes));
            var root = document.getDocumentElement();
            if (!"svg".equals(root.getLocalName()) || !"http://www.w3.org/2000/svg".equals(root.getNamespaceURI())) {
                throw new SAXException("Expected an SVG document");
            }
            if (root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "foreignObject").getLength() > 0) {
                throw new TranscoderException("SVG foreignObject content cannot be rasterized");
            }
            SvgRasterizer transcoder = new SvgRasterizer();
            transcoder.addTranscodingHint(KEY_EXECUTE_ONLOAD, false);
            transcoder.addTranscodingHint(KEY_ALLOWED_SCRIPT_TYPES, "");
            transcoder.addTranscodingHint(KEY_ALLOW_EXTERNAL_RESOURCES, false);
            transcoder.setErrorHandler(new ErrorHandler() {
                @Override public void warning(TranscoderException e) throws TranscoderException { throw e; }
                @Override public void error(TranscoderException e) throws TranscoderException { throw e; }
                @Override public void fatalError(TranscoderException e) throws TranscoderException { throw e; }
            });
            transcoder.transcode(new TranscoderInput(document), null);
            if (transcoder.image == null) throw new IllegalStateException("SVG renderer returned no image");
            return transcoder.image;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Secure SVG parser configuration is unavailable", e);
        }
    }

    @Override
    protected UserAgent createUserAgent() {
        return new SVGAbstractTranscoderUserAgent() {
            @Override
            public ExternalResourceSecurity getExternalResourceSecurity(ParsedURL resource, ParsedURL document) {
                return new NoLoadExternalResourceSecurity();
            }

            @Override
            public ScriptSecurity getScriptSecurity(String type, ParsedURL script, ParsedURL document) {
                return new NoLoadScriptSecurity(type);
            }
        };
    }

    @Override
    protected void setImageSize(float documentWidth, float documentHeight) {
        super.setImageSize(documentWidth, documentHeight);
        ImageResolver.checkDimensions(Math.round(width), Math.round(height));
    }

    @Override
    public BufferedImage createImage(int width, int height) {
        ImageResolver.checkDimensions(width, height);
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public void writeImage(BufferedImage image, TranscoderOutput output) {
        this.image = image;
    }
}
