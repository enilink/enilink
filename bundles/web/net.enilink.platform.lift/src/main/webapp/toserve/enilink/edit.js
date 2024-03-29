$.fn.editableform.template = '<form class="editableform" role="form">' //
		+ '<div><div class="row">' //
		+ '<div class="col-sm-9"><div class="editable-input"></div></div>' //
		+ '<div class="col-sm-3">' //
		+ '<div class="editable-lang" style="display:none;"><div class="input-group"><span class="input-group-addon">@</span><input class="form-control input-sm" style="max-width:40px" type="text" placeholder="lang"></div></div>' //
		+ '<div class="editable-buttons"></div></div></div></div>' //
		+ '<div class="editable-error-block"></div></form>';

enilink = $.extend(window.enilink || {}, {
	rdf : function(model) {
		function createFunc(orig, model) {
			return function() {
				// append model to args and call original method
				[].push.call(arguments, model);
				return orig.apply(null, arguments);
			};
		}
		;
		if (model) {
			var result = {};
			for ( var attr in enilink.rdf) {
				var elem = enilink.rdf[attr];
				if (typeof elem == "function") {
					result[attr] = createFunc(elem, model);
				} else {
					result[attr] = elem;
				}
			}
			return result;
		} else {
			return enilink.rdf;
		}
	}
});
(function() {
	function firstValue(rdfStmts) {
		var first;
		$.each(rdfStmts, function(i, po) {
			$.each(po, function(j, val) {
				if (val.length) {
					first = val[0];
				}
				return true;
			});
			return true;
		});
		return first;
	}

	function triggerLanguageInput(target, rdfStmts, language) {
		function supportsLang(l) {
			return l.type == "literal" && (!l.datatype || l.datatype == "komma:null" || l.datatype == "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString");
		}
		function showInput(lang) {
			target.off("shown.editable-lang").on("shown.editable-lang", function(e, editable) {
				var form = editable.container.$form;
				var editLang = form.find(".editable-lang");
				editLang.find("input").val(lang ? lang : "");
				editLang.show();
			});
		}
		var first = firstValue(rdfStmts);
		if (first && supportsLang(first)) {
			showInput(first.lang || language);
		}
	}

	function prepareEditable(target, rdfStmts, language) {
		triggerLanguageInput(target, rdfStmts, language);
	}

	function serializeRdfValue(value) {
		if (value !== undefined && typeof value.dump === "function") {
			return value.dump();
		}
		return value;
	}

	function defaultEditableOptions(rdfStmts, model) {
		var resourceProposal;
		return {
			type : "orion",
			onblur : "ignore",
			inputclass : "editable-form-control",
			emptyclass : "",
			emptytext : "",
			mode : "inline",
			toggle : "manual",
			savenochange : true,
			params : function(params) {
				var value = $.trim(params.value);
				if (resourceProposal && resourceProposal.proposal == value) {
					// use selected resource as value
					params.value = "<" + resourceProposal.resource + ">";
				} else {
					// set correct language
					var old = firstValue(rdfStmts);
					var lang = $(this).data('editable').container.$form.find(".editable-lang input").val();
					if (!lang && old) {
						lang = old.lang;
					}
					if (lang) {
						params.value = $.rdf.literal(value, {
							lang : lang
						});
					}
				}
				return params;
			},
			contentAssistProposalApplied : function(proposal) {
				if (proposal.resource) {
					resourceProposal = proposal;
				}
			},
			contentAssistProviders : [ {
				computeProposals : function(buffer, offset, context) {
					var dfd = new $.Deferred();
					enilink.rdf(model).propose({
						rdf : rdfStmts,
						query : buffer.substring(0, offset),
						index : offset
					}, function(results) {
						var proposals = [];
						$.each(results, function(index, value) {
							var positions = [];
							var escapePosition = value.insert ? offset + value.content.length : value.content.length;
							var proposal = {
								proposal : value.content,
								description : value.description,
								escapePosition : escapePosition,
								insert : value.insert
							};
							if (value.resource) {
								// this is a resource proposal
								proposal.resource = value.resource;
							}
							if (value.perfectMatch) {
								// use emphasis style
								proposal.style = "emphasis";
							} else {
								proposal.style = "noemphasis";
							}
							proposals.push(proposal);
						});
						dfd.resolve(proposals);
					});
					return dfd.promise();
				}
			} ]
		};
	}

	function computeSourceAndContinue(target, subject, predicate, model, f) {
		// try to automagically find possible choices
		var sparql = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> PREFIX owl: <http://www.w3.org/2002/07/owl#> " + // 
		"SELECT DISTINCT ?s { " + //
		"{ select ?sType {" + //
		"  { ?property rdfs:range ?sType FILTER (?sType != owl:Thing) }" + //
		"  UNION " + //
		"  { ?subject a [rdfs:subClassOf+ ?r] . ?r owl:onProperty ?property { ?r owl:allValuesFrom ?sType } UNION { ?r owl:someValuesFrom ?sType } }" + //
		"  }" + //
		"}" + //
		"?s a [rdfs:subClassOf* ?sType] ." + //
		"}";

		// insert subject and property as constants
		sparql = sparql.replace(/\?subject/g, subject);
		sparql = sparql.replace(/\?property/g, predicate);

		enilink.render('<div data-sparql="' + sparql + '" data-lift="sparql"><div about="?s" data-lift="rdf.label"></div></div>', {
			model : model
		}, function(html) {
			var items = $(html).find('[about]').map(function () {
				return { value : $(this).resourceAttr('about').toString(), text : $(this).text() };
			}).get();

			f(items);
		});
	}

	function doEdit(target, options) {
		// global deferred to allow the execution of
		// functions after doAdd finished
		var resultDeferred = new $.Deferred;
		var value = null;
		var triples = target.rdf().databank.triples();
		if (target.is('[data-resource-empty]')) {
			// empty resources that where for example created by the RDFa query
			// engine by applying the CSS class 'keep'
			value = "";
			if (triples.length) {
				triples[0].object = $.rdf.resource("<komma:null>");
			}
		} else if (target.is('[data-content-empty]')) {
			// empty literals that where for example created by the RDFa query
			// engine by applying the CSS class 'keep'
			value = "";
			if (triples.length) {
				triples[0].object = $.rdf.literal("", {
					datatype : "komma:null"
				});
			}
		} else {
			if (triples.length && (triples[0].object.type == "literal" ||
			// ensure that the editor has access to the real RDF value in case
			// of select, date etc.
			!String(target.data('type')).match(/text|orion|typeahead/))) {
				value = (triples[0].object.type == "literal") ? triples[0].object.value + "" : triples[0].object.toString();
			} else {
				value = target.closest("[content]").attr("content") || target.text().trim();
			}
		}

		var model = enilink.contextModel(target);
		var rdfStmts = $.rdf.dump(triples);
		options = $.extend(defaultEditableOptions(rdfStmts, model), {
			title : "Edit value",
			value : value,
			display : function(value, sourceData, response) {
				if (!response) {
					// sourceData is only available if 'source' option was used
					response = sourceData;
				}
				if (response && response.html) {
					var newNode = $(response.html);
					// replaceWith does not work for some reason here
					// target.replaceWith(newNode);
					newNode.insertAfter(target);
					newNode.trigger("enilink-edit-updated");
					target.remove();
					if (enilink.options["ui.autoEdit"]) {
						enableEdit(newNode);
					}
					resultDeferred.resolve(newNode);
				}
				return null;
			},
			url : function(params) {
				var d = new $.Deferred;
				enilink.rdf(model).setValue({
					rdf : rdfStmts,
					value : serializeRdfValue(params.value),
					template : target.attr("data-t"),
					what : target.closest("[data-t-path]").attr("data-t-path")
				}, function(result) {
					if (result.msg) {
						d.reject(result.msg);
						resultDeferred.reject(result.msg);
					} else {
						if (! result.html) {
							resultDeferred.resolve(true);
						}
						d.resolve(result);
					}
				});
				return d.promise();
			}
		}, options || {});

		var lang = target.closest("[lang], [data-lang]").map(function() {
			var $this = $(this);
			return $this.attr("lang") || $this.attr("data-lang");
		}).get();

		prepareEditable(target, rdfStmts, lang);

		// try to automagically find possible choices
		if (String(target.data('type')).match(/select/) && !options.source && !target.data('source') && triples.length) {
			computeSourceAndContinue(target, triples[0].subject.toString(), triples[0].property.toString(), model, function (items) {
				if (String(target.data('type')).match(/select2/)) {
					items = items.map(function (item) {
						return { id : item.value, text : item.text };
					});
				}
				options.source = items;

				target.editable(options);
				target.editable("show");
			});
		} else {
			target.editable(options);
			target.editable("show");
		}
		return resultDeferred.promise();
	}

	function doAdd(self, options) {
		// global deferred to allow the execution of
		// functions after doAdd finished
		var resultDeferred = new $.Deferred;
		var e = self.closest("[property], [data-property]")
		var property = e.attr("property") || e.attr("data-property");
		var predicate = property;
		if (!predicate) {
			e = self.closest("[rel], [data-rel]");
			predicate = e.attr("rel") || e.attr("data-rel");
		}
		e = self.closest("[resource], [about]");
		var subject = e.attr("resource") || e.attr("about");
		var object;
		if (property) {
			object = $.rdf.literal("", {
				datatype : "komma:null"
			});
		} else {
			object = $.rdf.resource("<komma:null>");
		}
		if (subject && predicate) {
			var model = self.closest("[data-model]").attr("data-model");
			// explicit creation of blank node objects
			if (subject.match(/^_:/)) {
				subject = $.rdf.blank(subject);
			}
			var nsMap = self.xmlns();
			var triple = $.rdf.triple(subject, predicate, object, {
				namespaces : nsMap
			});
			var stmt = $.rdf.databank([ triple ]).dump();
			options = $.extend(defaultEditableOptions(stmt, model), {
				display : false,
				success : function(response) {
					if (options.onadd) {
						$.when(options.onadd(response)).then(function(value) {
							resultDeferred.resolve(value);
						}).fail(function(reason) {
							resultDeferred.reject(reason);
						});
						return;
					}
					if (response.html) {
						var newNode = $(response.html);
						var container = self.attr("data-container");
						if (container) {
							$(container).append(newNode);
						} else {
							var before = self.attr("data-before");
							if (before) {
								var beforeNode = $(before);
								newNode.insertBefore(beforeNode);
							} else {
								newNode.insertBefore(self);
							}
						}
						if (enilink.options["ui.autoEdit"] && newNode.is(".editable")) {
							enableEdit(newNode);
						}
						newNode.trigger("enilink-edit-added");
						resultDeferred.resolve(newNode);
					}
				},
				value : "",
				title : "Add value",
				url : function(params) {
					var d = new $.Deferred;
					enilink.rdf(model).setValue({
						rdf : stmt,
						value : serializeRdfValue(params.value),
						template : self.attr("data-add"),
						what : self.closest("[data-t-path]").attr("data-t-path")
					}, function(result) {
						if (result.msg) {
							d.reject(result.msg);
							resultDeferred.reject(result.msg);
						} else {
							d.resolve(result);
						}
					});
					return d.promise();
				}
			}, options || {});

			var lang = self.closest("[lang], [data-lang]").map(function() {
				var $this = $(this);
				return $this.attr("lang") || $this.attr("data-lang");
			}).get();

			prepareEditable(self, stmt, lang);

			// try to automagically find possible choices
			if (String(self.data('type')).match(/select/) && !options.source && !self.data('source')) {
				computeSourceAndContinue(self, triple.subject.toString(), triple.property.toString(), model, function (items) {
					if (String(self.data('type')).match(/select2/)) {
						items = items.map(function (item) {
							return { id : item.value, text : item.text };
						});
					}
					options.source = items;

					self.editable(options);
					self.editable("show");
				});
			} else {
				self.editable(options);
				self.editable("show");
			}
		} else {
			resultDeferred.reject("Subject and predicate must be defined.")
		}
		return resultDeferred.promise();
	}
	function addHandler(e) {
		e.stopPropagation();
		e.preventDefault();
		var self = $(this);
		var onadd = self.data("onadd");
		var options = {};
		if (onadd) {
			onadd = eval.call(window, "(" + onadd + ")");
			if (typeof onadd === "function") {
				options.onadd = onadd;
			}
		}
		doAdd(self, options);
	}

	function enableAdd(nodeOrSelector) {
		// allow to use selectors or DOM nodes as arguments
		nodeOrSelector = (nodeOrSelector instanceof jQuery) ? nodeOrSelector : $(nodeOrSelector);
		nodeOrSelector.click(addHandler);
	}

	var buttons;
	function hideButtons() {
		var lastTarget = buttons.data("target");
		if (lastTarget) {
			lastTarget.removeClass("focused");
		}
		buttons.hide();
	}

	function enableEdit(nodeOrSelector) {
		// allow to use selectors or DOM nodes as arguments
		nodeOrSelector = (nodeOrSelector instanceof jQuery) ? nodeOrSelector : $(nodeOrSelector);
		nodeOrSelector.filter(function() {
			var self = $(this);
			return self.is("[rel],[rev],[property]") || self.parent().closest("[rel],[resource],[href],[about]").not("[resource],[href]").is("[rel],[rev]");
		}).hover(function(e) {
			e.stopPropagation();
			var target = $(e.target).closest("[about],[resource],[property]");
			if (target.length == 0) {
				return;
			}
			if (!target.is(buttons.data("target")) || !buttons.is(":visible")) {
				var lastTarget = buttons.data("target");
				if (lastTarget) {
					lastTarget.removeClass("focused");
				}
				target.addClass("focused");
				buttons.data("target", target);
				var top = target.offset().top;
				var left;
				if (target.innerWidth() <= buttons.outerWidth()) {
					left = target.offset().left + target.outerWidth() - 5;
				} else {
					left = target.offset().left + target.innerWidth() - buttons.outerWidth();
				}

				// position buttons to the right of target
				buttons.css({
					position : "absolute",
					marginLeft : 0,
					marginTop : 0,
					top : top,
					left : left
				});
				buttons.show();
			}
		}, function(e) {
			var relatedTarget = $(e.relatedTarget);
			if (!relatedTarget || (relatedTarget != buttons && relatedTarget.closest("#buttons").length == 0)) {
				hideButtons();
			}
		});
	}

	// allow access through global enilink object
	enilink = window.enilink || {};
	enilink.ui = $.extend(enilink.ui || {}, {
		enableEdit : enableEdit,
		enableAdd : enableAdd,
		edit : doEdit,
		add : doAdd,
		defaultEditableOptions : defaultEditableOptions
	});
	enilink.options = $.extend({
		"ui.autoEdit" : true
	}, enilink.options || {});

	$(function() {
		buttons = $("#buttons");
		// relocate buttons for absolute positioning
		buttons.css("z-index", "1000000")
		buttons.appendTo("body");

		function editHandler(e) {
			e.stopPropagation();
			e.preventDefault();
			var target = $("#buttons").data("target");
			if (target) {
				hideButtons();
				doEdit(target);
			}
		}
		$("#buttons .button-edit").click(editHandler);

		function remove() {
			var target = $("#buttons").data("target");
			if (target) {
				var model = target.closest("[data-model]").attr("data-model");
				var toDelete = target.rdf().databank.dump();
				enilink.rdf(model).removeValue(toDelete, function(success) {
					if (success) {
						target.remove();
					}
				});
			}
			hideButtons();
		}
		$("#buttons .button-remove").click(remove);

		if (enilink.options["ui.autoEdit"]) {
			enilink.ui.enableEdit(".editable[about],.editable[resource],.editable[property]");
			enilink.ui.enableAdd("[data-add]");
		}
	});
})();
