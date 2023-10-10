package us.codecraft.webmagic.selector;

/**
 * Convenient methods for selectors.<br>
 *
 * @author code4crafter@gmail.com <br>
 * @since 0.2.1
 */
public abstract class Selectors {

    public static RegexSelector regex(String expr) {
        return SelectorFactory.getInstatnce().newRegexSelector(expr);
    }

    public static RegexSelector regex(String expr, int group) {
        return SelectorFactory.getInstatnce().newRegexSelector(expr, group);
    }

    public static SmartContentSelector smartContent() {
        return SelectorFactory.getInstatnce().newSmartContentSelector();
    }

    public static CssSelector $(String expr) {
        return new CssSelector(expr);
    }

    public static CssSelector $(String expr, String attrName) {
        return new CssSelector(expr, attrName);
    }

    public static XpathSelector xpath(String expr) {
        return SelectorFactory.getInstatnce().newXpathSelector(expr);
    }

    public static AndSelector and(Selector... selectors) {
        return new AndSelector(selectors);
    }

    public static OrSelector or(Selector... selectors) {
        return new OrSelector(selectors);
    }

    public static TextContentSelector text() {
        return new TextContentSelector();
    }

    public static TextContentSelector text(String newlineSeperator) {
        return new TextContentSelector(newlineSeperator);
    }

    public static void main(String[] args) {
        String s = "a";
        or(regex("<title>(.*)</title>"), xpath("//title"), $("title")).select(s);
    }

}