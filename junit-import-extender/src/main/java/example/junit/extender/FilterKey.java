package example.junit.extender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structural comparison of framework-generated filters, including nested/reordered ANDs. */
final class FilterKey {
  private final String text;
  private int position;
  private FilterKey(String text) {
    this.text = text;
  }

  static String of(String text) {
    if (text == null)
      return "";
    FilterKey parser = new FilterKey(text);
    Node node = parser.read();
    if (parser.position != text.length())
      throw new IllegalArgumentException("Trailing filter content");
    return node.key();
  }

  private Node read() {
    if (position >= text.length() || text.charAt(position++) != '(')
      throw new IllegalArgumentException("Invalid filter");
    char operator = text.charAt(position);
    if (operator == '&' || operator == '|' || operator == '!') {
      position++;
      List<Node> children = new ArrayList<>();
      while (position < text.length() && text.charAt(position) == '(') {
        Node child = read();
        if (operator != '!' && child.operator == operator)
          children.addAll(child.children);
        else
          children.add(child);
      }
      if (children.isEmpty() || (operator == '!' && children.size() != 1))
        throw new IllegalArgumentException("Invalid filter operands");
      if (position >= text.length() || text.charAt(position++) != ')')
        throw new IllegalArgumentException("Unclosed filter");
      return new Node(operator, null, children);
    }
    int start = position;
    while (position < text.length() && text.charAt(position) != ')') {
      if (text.charAt(position++) == '\\' && position < text.length())
        position++;
    }
    if (position >= text.length())
      throw new IllegalArgumentException("Unclosed filter atom");
    String atom = text.substring(start, position++);
    return new Node('=', atom, List.of());
  }

  private record Node(char operator, String atom, List<Node> children) {
    String key() {
      if (atom != null)
        return "(" + atom + ")";
      List<String> keys = new ArrayList<>();
      for (Node child : children) keys.add(child.key());
      if (operator != '!')
        Collections.sort(keys);
      return "(" + operator + String.join("", keys) + ")";
    }
  }
}
