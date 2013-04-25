package org.netbeans.modules.java.code.recommenders;

import com.sun.source.tree.MemberSelectTree;
import static com.sun.source.tree.Tree.Kind.MEMBER_SELECT;
import com.sun.source.util.TreePath;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.swing.ImageIcon;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.eclipse.recommenders.calls.DefinitionSite.Kind;
import static org.eclipse.recommenders.calls.Helpers.compareByRelevance;
import static org.eclipse.recommenders.calls.Helpers.filterByMinRelevance;
import org.eclipse.recommenders.calls.ICallRecommendationNet;
import org.eclipse.recommenders.calls.JayesCallRecommendationNet;
import org.eclipse.recommenders.calls.Recommendation;
import org.eclipse.recommenders.commons.bayesnet.BayesianNetwork;
import org.eclipse.recommenders.utils.Constants;
import org.eclipse.recommenders.utils.Zips;
import org.eclipse.recommenders.utils.names.IMethodName;
import org.eclipse.recommenders.utils.names.ITypeName;
import org.eclipse.recommenders.utils.names.VmTypeName;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.JavaSource.Phase;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.api.java.source.Task;
import org.netbeans.modules.editor.java.JavaCompletionItem;
import org.netbeans.spi.editor.completion.CompletionItem;
import org.netbeans.spi.editor.completion.CompletionProvider;
import org.netbeans.spi.editor.completion.CompletionResultSet;
import org.netbeans.spi.editor.completion.CompletionTask;
import org.netbeans.spi.editor.completion.support.AsyncCompletionQuery;
import org.netbeans.spi.editor.completion.support.AsyncCompletionTask;
import org.netbeans.spi.editor.completion.support.CompletionUtilities;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.Exceptions;

/**
 *
 * @author lahvac
 */
public class RecommenderCodeCompletion extends AsyncCompletionQuery {

    private static final Logger LOG = Logger.getLogger(RecommenderCodeCompletion.class.getName());
    private final ZipFile zip;

    public RecommenderCodeCompletion(ZipFile zip) {
        this.zip = zip;
    }

    @Override
    protected void query(final CompletionResultSet resultSet, Document doc, final int caretOffset) {
        JavaSource js = JavaSource.forDocument(doc);

        try {
            if (js == null) return ;
            
            js.runUserActionTask(new Task<CompilationController>() {
                @Override public void run(CompilationController parameter) throws Exception {
                    parameter.toPhase(Phase.RESOLVED); //XXX: should check the content and prevent attribution if possible
                    TreePath pathFor = parameter.getTreeUtilities().pathFor(caretOffset);

                    resolveCodeCompletion(parameter, pathFor, caretOffset, resultSet);
                }
            }, true);
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        } finally {
            resultSet.finish();
        }
    }

    private void resolveCodeCompletion(CompilationInfo info, TreePath path, int subtitutionOffset, CompletionResultSet resultSet) throws Exception {
        switch (path.getLeaf().getKind()) {
            case MEMBER_SELECT:
                MemberSelectTree mst = (MemberSelectTree) path.getLeaf();
                TypeMirror type = info.getTrees().getTypeMirror(new TreePath(path, mst.getExpression()));
                TypeElement clazz = (TypeElement) info.getTypes().asElement(type);//XXX
                ITypeName typeName = VmTypeName.get("L" + info.getElements().getBinaryName(clazz).toString().replace('.', '/'));

                ZipEntry entry = zip.getEntry(Zips.path(typeName, ".data"));

                if (entry == null) {
                    return ;
                }

                BayesianNetwork bayesNet = BayesianNetwork.read(zip.getInputStream(entry));
                ICallRecommendationNet callNet = new JayesCallRecommendationNet(typeName, bayesNet);
                callNet.reset();
                callNet.setObservedEnclosingMethod(Constants.UNKNOWN_METHOD);
                callNet.setObservedKind(Kind.UNKNOWN);
//                if (query.definition != null && !query.definition.equals(UNKNOWN_METHOD)) {
//                    callNet.setObservedDefinition(query.definition);
//                }
                callNet.setObservedCalls(new HashSet<IMethodName>());

                List<Recommendation<IMethodName>> recommendations = new ArrayList<>(callNet.getRecommendedCalls(filterByMinRelevance(0.1d), compareByRelevance(), 5));

                Collections.sort(recommendations, new Comparator<Recommendation<?>>() {
                    @Override public int compare(Recommendation<?> o1, Recommendation<?> o2) {
                        return (int) Math.signum(o1.getRelevance() - o2.getRelevance());
                    }
                });

                int priority = 0;
                
                // query the recommender:
                for (Recommendation<IMethodName> r : recommendations) {
                    ExecutableElement method = resolveMethod(info, r.getProposal());

                    if (method == null) {
                        LOG.log(Level.INFO, "Cannot resolve {0}.", r.toString());
                        continue;
                    }
                    
                    JavaCompletionItem i = JavaCompletionItem.createExecutableItem(info, method, (ExecutableType) method.asType()/*XXX*/, subtitutionOffset, null, false, false, false, false, false, -1, false, null);

                    resultSet.addItem(new MethodCompletionItem(i, r.getRelevance(), priority++));
                }
        }
    }

    private static ExecutableElement resolveMethod(CompilationInfo info, IMethodName method) {
        TypeElement type = ElementHandle.createTypeElementHandle(ElementKind.CLASS, method.getDeclaringType().toString().substring(1).replace('/', '.')).resolve(info);

        if (type == null) return null;

        String methodName = method.getName();
        String signature = method.getDescriptor();

        for (ExecutableElement enclosedMethod : ElementFilter.methodsIn(info.getElements().getAllMembers(type))) {
            if (!enclosedMethod.getSimpleName().contentEquals(methodName)) continue;

            if (signature.equals(SourceUtils.getJVMSignature(ElementHandle.create(enclosedMethod))[2]))
                return enclosedMethod;
        }

        return null;
    }

    private static final class MethodCompletionItem implements CompletionItem {

        private final JavaCompletionItem delegate;
        private final String relevance;
        private final int priority;

        public MethodCompletionItem(JavaCompletionItem delegate, double relevance, int priority) {
            this.delegate = delegate;
            this.relevance = " - " + String.format("%2.2f", relevance * 100) + "%";
            this.priority = priority;
        }

        @Override
        public void defaultAction(JTextComponent component) {
            delegate.defaultAction(component);
        }

        @Override
        public void processKeyEvent(KeyEvent evt) {
            delegate.processKeyEvent(evt);
        }

        private <T> T invokeDelegateMethod(String name, Class<T> result) {
            try {
                Method method = JavaCompletionItem.class.getDeclaredMethod(name);

                method.setAccessible(true);
                return result.cast(method.invoke(delegate));
            } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                Exceptions.printStackTrace(ex);
            }

            return null;
        }
        @Override
        public int getPreferredWidth(Graphics g, Font defaultFont) {
            String left = invokeDelegateMethod("getLeftHtmlText", String.class) + relevance;
            String right = invokeDelegateMethod("getRightHtmlText", String.class);

            return CompletionUtilities.getPreferredWidth(left, right, g, defaultFont);
        }

        @Override
        public void render(Graphics g, Font defaultFont, Color defaultColor, Color backgroundColor, int width, int height, boolean selected) {
            ImageIcon icon = invokeDelegateMethod("getIcon", ImageIcon.class);
            String left = invokeDelegateMethod("getLeftHtmlText", String.class) + relevance;
            String right = invokeDelegateMethod("getRightHtmlText", String.class);

            CompletionUtilities.renderHtml(icon, left, right, g, defaultFont, defaultColor, width, height, selected);
        }

        @Override
        public CompletionTask createDocumentationTask() {
            return delegate.createDocumentationTask();
        }

        @Override
        public CompletionTask createToolTipTask() {
            return delegate.createToolTipTask();
        }

        @Override
        public boolean instantSubstitution(JTextComponent component) {
            return false;
        }

        @Override
        public int getSortPriority() {
            return -1000 - priority;
        }

        @Override
        public CharSequence getSortText() {
            return delegate.getSortText();
        }

        @Override
        public CharSequence getInsertPrefix() {
            return delegate.getInsertPrefix();
        }

    }

    @MimeRegistration(mimeType="text/x-java", service=CompletionProvider.class)
    public static final class RecommenderCodeCompletionProvider implements CompletionProvider {

        private ZipFile zip;

        public RecommenderCodeCompletionProvider() {
            try {
                File data = InstalledFileLocator.getDefault().locate("modules/data/jre-1.0.0-call.zip", "org.netbeans.modules.java.code.recommenders.lib", false);

                if (data != null)
                    zip = new ZipFile(data);
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }


        @Override
        public CompletionTask createTask(int queryType, JTextComponent component) {
            return new AsyncCompletionTask(new RecommenderCodeCompletion(zip), component);
        }

        @Override
        public int getAutoQueryTypes(JTextComponent component, String typedText) {
            return 0;
        }

    }
}
