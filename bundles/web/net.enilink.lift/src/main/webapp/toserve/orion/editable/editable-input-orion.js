/**
 * Eclipse Orion input
 * 
 * @class orion
 * @extends abstractinput
 * @final
 */
(function($) {
	var Orion = function(options) {
		this.init("orion", options, Orion.defaults);
	};

	$.fn.editableutils.inherit(Orion, $.fn.editabletypes.abstractinput);

	function createEditor(success) {
		var parent = this.$input.get(0);
		var self = this;
		require([ "orion/editor/edit", "orion/Deferred",
		// "orion/editor/textMateStyler", "enilink/editor/testGrammar"
		], function(edit, Deferred, textMateStyler, testGrammar) {
			var editor = edit({
				parent : parent,
				showLinesRuler : true,
				showAnnotationRuler : false,
				showOverviewRuler : true,
				showFoldingRuler : false,
				wrapMode : false
			});
			// automatically resize text view, use height instead of line count
			// to support wrapMode = true
			var lastVisibleHeight;
			var scrollDiv = editor.getTextView()._scrollDiv;
			editor.getTextView().addEventListener("Modify", function(evt) {
				// TODO check why scrollDiv.clientHeight is too small after
				// initialization
				// of the editor
				var totalHeight = scrollDiv.clientHeight;
				var visibleHeight = Math.max(50, Math.min(totalHeight, 400));
				if (visibleHeight != lastVisibleHeight) {
					self.$input.height(visibleHeight);
					editor.getTextView().resize();
					lastVisibleHeight = visibleHeight;
				}
			});

			// prevent events from bubbling up to ancestors
			self.$input.off("click.edit mousedown.edit").on(
					"click.edit mousedown.edit", function(e) {
						e.stopPropagation();
					});

			// move content assist, since it is appended to body by default
			$(".contentassist").filter(":last").appendTo(parent);

			// register contentAssist providers
			var contentAssistProviders = self.options.contentAssistProviders;
			if (contentAssistProviders) {
				var contentAssist = editor._contentAssist;
				contentAssist.addEventListener("Activating", function() {
					contentAssist.setProviders(contentAssistProviders);
				});
				var appliedFunc = self.options.contentAssistProposalApplied;
				if (typeof appliedFunc !== "function") {
					appliedFunc = null;
				}
				contentAssist.addEventListener("ProposalApplied", function(
						event) {
					var proposal = event.data.proposal;
					if (!proposal.insert) {
						// support replacement of complete text
						var textView = editor.getTextView();
						textView.setText(proposal.proposal, 0, textView
								.getText().length);
					}
					if (appliedFunc) {
						appliedFunc(proposal);
					}
				});
			}
			/*
			 * var syntaxHighlighter = { styler : null, highlight :
			 * function(editor) { if (this.styler) { this.styler.destroy();
			 * this.styler = null; } var textView = editor.getTextView(); var
			 * annotationModel = editor.getAnnotationModel(); this.styler = new
			 * textMateStyler.TextMateStyler(textView, new
			 * testGrammar.TestGrammar()); } };
			 * syntaxHighlighter.highlight(editor);
			 */
			success(editor);
		});
	}

	$.extend(Orion.prototype, {
		render : function() {
			this.setClass();

			var dfd = new $.Deferred();
			var self = this;
			require([ "/classpath/orion/built-editor-amd.js" ], function() {
				createEditor.call(self, function(editor) {
					require([ "orion/keyBinding" ], function(keyBinding) {
						var textView = editor.getTextView();
						// ctrl + enter
						textView.setKeyBinding(new keyBinding.KeyBinding(13,
								true), "editable.commitMode"); //$NON-NLS-0$
						textView.setAction("editable.commitMode", function() {
							self.$input.closest("form").submit();
							return true;
						}, {
							name : "Commit"
						});

						self.editor = editor;
						dfd.resolve();
					});
				});
			});
			return dfd.promise();
		},

		value2input : function(value) {
			this.textValue = value;
		},

		input2value : function() {
			return this.editor.getText();
		},

		postrender : function() {
			// setText requires visibility of editor
			var text = this.textValue || "";
			this.editor.getTextView().invokeAction("toggleWrapMode");
			this.editor.setText(text);
			this.editor.setCaretOffset(text.length);
		},

		activate : function() {
			this.editor.getTextView().focus();
		},

		value2html : function(value, element) {
			var html = '', lines;
			if (value) {
				lines = value.split("\n");
				for (var i = 0; i < lines.length; i++) {
					lines[i] = $('<div>').text(lines[i]).html();
				}
				html = lines.join('<br>');
			}
			$(element).html(html);
		},

		html2value : function(html) {
			if (!html) {
				return '';
			}

			var regex = new RegExp(String.fromCharCode(10), 'g');
			var lines = html.split(/<br\s*\/?>/i);
			for (var i = 0; i < lines.length; i++) {
				var text = $('<div>').html(lines[i]).text();

				// Remove newline characters (\n) to avoid them being converted
				// by value2html() method
				// thus adding extra <br> tags
				text = text.replace(regex, '');

				lines[i] = text;
			}
			return lines.join("\n");
		}
	});

	Orion.defaults = $
			.extend(
					{},
					$.fn.editabletypes.abstractinput.defaults,
					{
						/**
						 * @property tpl
						 * @default
						 * 
						 * <pre></pre>
						 */
						tpl : '<pre style="height:7em; padding: 0; word-wrap: inherit; word-break: inherit; white-space: inherit; border: none"></pre>',
						/**
						 * @property inputclass
						 * @default input-large
						 */
						inputclass : 'input-large',
						/**
						 * Placeholder attribute of input. Shown when input is
						 * empty.
						 * 
						 * @property placeholder
						 * @type string
						 * @default null
						 */
						placeholder : null,
						/**
						 * Registered providers for content assistance.
						 * 
						 * @property contentAssistProviders
						 * @type Array
						 * @default null
						 */
						contentAssistProviders : null,
						/**
						 * Function which is called after a proposal has been
						 * applied.
						 * 
						 * @property contentAssistProposalApplied
						 * @type function
						 * @default null
						 */
						contentAssistProposalApplied : null
					});

	$.fn.editabletypes.orion = Orion;
}(window.jQuery));