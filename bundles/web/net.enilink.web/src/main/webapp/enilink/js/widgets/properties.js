define([ "flight/lib/component" ], function(defineComponent) {
	var testObjectProperty = '<span class="clearable optional">' + //
	'<span about="?objectProperty" data-bind="owl:ObjectProperty as ?objectProperty"></span>' + //
	'<span about="?property" class="union">' +
	'	<span rel="rdf:type" resource="?objectProperty"></span>' + // 
	'	<span data-pattern="?property rdfs:range rdfs:Class"></span>' + // 
	'</span>' + //
	'<span rel="rdf:type" class="not-exists union"><span resource="owl:AnnotationProperty"></span><span resource="owl:DatatypeProperty"></span></span>' + //
	'</span>';

	function addProperty(target, property) {
		var propertyUri = $.rdf.resource(property).value;
		var existing = target.find('[data-property]').filter(function() {
			return $(this).resourceAttr("data-property").value == propertyUri;
		});
		if (existing.length == 0) {
			existing = target.find('[data-rel]').filter(function() {
				return $(this).resourceAttr("data-rel").value == propertyUri;
			});
		}
		if (existing.length > 0) {
			existing.click();
			return;
		}
		var template = $('<div about="?this" data-lift="rdfa?queryAsserted=false">' + //
		// ensure that at least ?this and ?property are bound within a result
		'<div class="clearable"><div data-bind="?this as ?this"></div><div data-bind="?property as ?property"></div></div>' + //
		testObjectProperty + //
		'<div id="content" class="optional"><div data-embed="widgets/properties" data-template="property"></div></div>' + //
		'</div>').prefix({
			rdf : "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
			rdfs : "http://www.w3.org/2000/01/rdf-schema#",
			owl : "http://www.w3.org/2002/07/owl#"
		});

		enilink.render({
			what : $("<div>").append(template).html(),
			bind : {
				property : property
			}
		}, {
			resource : enilink.contextResource(target),
			model : enilink.contextModel(target)
		}, function(html) {
			var content = $(html).find("#content").children();
			target.append(content);
			var addBtn = content.find("[data-add]");
			enilink.ui.enableAdd(addBtn);
			addBtn.click();
		})
	}

	var addPropertyBtn = defineComponent(function() {
		this.attributes({
			target : null
		});

		this.onClick = function() {
			var self = this.$node.closest("li[about]");
			var property = self.resourceAttr("about").value.toString();
			addProperty(self.closest(".widget").find(this.attr.target), property);
		}

		this.after('initialize', function() {
			this.on('click', this.onClick);
		});
	});

	var propertyInput = defineComponent(function() {
		this.attributes({
			target : null
		});
		this.installTypeahead = function() {
			var thisComponent = this;
			var self = this.$node;
			var model = self.closest("[data-model]").attr("data-model");
			var timeoutID;
			self.typeahead({
				minLength : 2
			}, {
				source : function(query, cb) {
					if (timeoutID) {
						clearTimeout(timeoutID);
					}
					timeoutID = window.setTimeout(function() {
						var subject = self.closest("[about]").resourceAttr("about");
						var stmt = $.rdf.triple(subject, "<komma:null>", "<komma:null>").dump();
						if (query.charAt(0) == '<') {
							query = query.slice(1);
						}
						enilink.rdf(model).propose({
							rdf : stmt,
							query : query
						}, cb);
					}, 500);
				},
				displayKey : function(v) {
					return v.content;
				},
				templates : {
					suggestion : function(v) {
						var t = $('<p />').css("white-space", "nowrap").text(v.label);
						if (v.perfectMatch) {
							t.css("font-weight", "bold");
						}
						return $("<span />").append(t);
					}
				}
			}).on("typeahead:autocompleted typeahead:selected", function(evt, proposal) {
				if (proposal.resource) {
					addProperty(self.closest(".widget").find(thisComponent.attr.target), proposal.resource);
				} else if (proposal.content.match(/[:]$/)) {
					// automatically trigger typeahead
					setTimeout(function() {
						self.typeahead("val", "");
						self.typeahead("val", proposal.content);
					}, 50);
				}
			});
		}

		this.after('initialize', function() {
			this.installTypeahead();
		});
	});

	var showInversePropertiesBtn = defineComponent(function() {
		this.onClick = function() {
			var node = this.$node;
			enilink.render('<div data-lift="rdfa"><div data-embed="widgets/inverse-properties"></div></div>', {
				model : enilink.contextModel(node),
				resource : enilink.contextResource(node)
			}, function(html) {
				node.replaceWith(html);
			});
		}

		this.after('initialize', function() {
			this.on('click', this.onClick);
		});
	});

	return defineComponent(function() {
		this.attributes({
			target : null
		});
		this.after('initialize', function() {
			this.on('attach-components', function() {
				addPropertyBtn.attachTo(this.$node.find(".add-property"), {
					target : this.attr.target
				});
				propertyInput.attachTo(this.$node.find(".input-property").not('.tt-input, .tt-hint'), {
					target : this.attr.target
				});
				showInversePropertiesBtn.attachTo(this.$node.find(".show-inverse-properties"));
			});
		});
	});
});